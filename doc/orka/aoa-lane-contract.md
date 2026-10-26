# AOA lane (lane E): wire contract between scrcpy-server and the provider

Status: normative for orka-backend `provider/aoausb` and `provider/aoalane` (host side) and for the
scrcpy fork's `com.genymobile.scrcpy.aoa` (`Research-Warrant/scrcpy`, phone side). The file is kept
byte-identical at fork `doc/orka/aoa-lane-contract.md` and orka-backend `docs/aoa-lane-contract.md`:
any change, wire or wording, edits both copies, one PR per repository, linked; a wire change also
bumps the header `version` byte (§3.2). If code and this file disagree, fix one of them in the same
change. History: derived from orka-POC `docs/specs/scrcpy-video-in-gads/aoa-lane-contract.md` at
`c3d7b49`.

Background: the POC spec's Stage 4 and §8.5 (option A works on Android 13-16 on S21 FE and S21+;
Android 12 fails with `SecurityException`), orka-POC `notes/lane-e-aoa.md` (facts F1-F12, traps
T1-T13) and the spike (orka-POC `tools/aoa-spike/accprobe/AccProbe.java`, phone side) are the research
record in orka-POC, not shipped. The reference implementation is orka-backend `provider/aoausb` and
`provider/aoalane` (host) and the fork's `com.genymobile.scrcpy.aoa` (phone). Where this contract says
"as the spike", reuse that behaviour; it is measured, not assumed.

Owner decisions this contract implements:
- Video and control leave adbd entirely on Android 13+ (SDK >= 33). adb stays for launching the server
  and for everything else (IME, files, apps, screenshots).
- Android 12, and any AOA failure, falls back to lane D automatically, per phone.
- No Wi-Fi, no Ethernet-over-USB. Simple and readable.

---

## 1. Server option

### 1.1 The option

`aoa=<nonce>` enables the lane. `<nonce>` is exactly 8 lowercase hex digits, not `00000000`. The
provider picks a fresh random value for every launch; the server echoes it in HELLO and expects it back
in HELLO_HOST (§3.3). It lets both sides drop stale bytes left on the bulk pair by an earlier server.

The provider's lane E command (ModeVideo; `tunnel_forward=true` is replaced by `aoa=<nonce>`):

```
CLASSPATH=/data/local/tmp/orka-scrcpy.jar app_process / com.genymobile.scrcpy.Server 5.0.1 \
  scid=<8 hex> aoa=<8 hex> audio=false [video_codec=h265] [video_bit_rate=N] [max_size=N] [max_fps=N] \
  [video_codec_options=i-frame-interval=N] clipboard_autosync=false cleanup=false
```

Length with the worst-case scid (`MaxShellCommandLen` = 290 stays the bound, enforced by the existing
test, which gains the lane E cases):

| Profile | Lane D today | Lane E |
|---|---|---|
| H.265, 720 px, 25 fps, 1 Mbit/s, i-frame-interval 1 (dev server) | 278 | 271 |
| H.264 `DefaultProfile()` (1280 px, 30 fps, 8 Mbit/s) | 239 | 232 |
| H.265, 1920 px, 60 fps, 16 Mbit/s, i-frame-interval 2 (test's largest) | 280 | 273 |

Never pass `frame-rate` (unchanged rule). `log_level` stays at its default.

### 1.2 Combination with other options

| Option | With `aoa` |
|---|---|
| `scid` | Required, not `-1`. The server derives the expected accessory serial from it (§2.3), and the provider kills by it (`KillCommand`, unchanged) |
| `tunnel_forward` | Must be absent or `false`. `true` is rejected (exit 29) |
| `video` | Must be `true` (default). The video stream is channel 1 |
| `audio` | Must be `false`; the provider always passes `audio=false`. `true` is rejected (exit 29). Channel 3 is reserved for audio, unused |
| `control` | `true` (default): the control stream is channel 2, both directions. `false`: no channel 2 |
| `send_dummy_byte`, `send_device_meta`, `send_stream_meta`, `send_frame_meta`, `raw_stream` | Never passed. Defaults apply, so channel 1 is byte-identical to the forward-mode video socket (§3.5) |
| `list_*` | List modes exit before opening a connection. `aoa` is still validated when present (§1.2 rules apply); the provider never combines them |
| `cleanup` | Always `false` (unchanged); no `CleanUp` process |

Without `aoa`, the fork behaves exactly like upstream 5.0.1 (same options, same wire bytes). Lane D
runs on the same jar.

### 1.3 Exit codes and the stderr line

Every lane E failure exits with a dedicated code and writes one line to stderr through `Ln.e`:

```
[server] ERROR: aoa: <REASON>: <detail>
```

The exit code is authoritative: the provider reads it from the shell v2 exit status
(`adbcmd.ExitCode`). The line is for logs only. Codes 20-29 are lane E's; upstream keeps 0 and 1.

| Code | REASON | When |
|---|---|---|
| 0 | - | Host sent BYE (§4.4), or the server ended normally |
| 1 | - | Upstream failure (encoder, capture, options), unchanged |
| 20 | `SDK_TOO_OLD` | `Build.VERSION.SDK_INT < 33`. Checked first; the accessory is never touched |
| 21 | `NO_ACCESSORY` | `getCurrentAccessory()` still null after 5 s (polled every 250 ms) |
| 22 | `NOT_OUR_ACCESSORY` | Accessory serial unreadable, or not `orka-<scid>` (§2.3) |
| 23 | `PERMISSION_DENIED` | `SecurityException` from `getCurrentAccessory` or `openAccessory` (the Android 12 result) |
| 24 | `OPEN_FAILED` | `openAccessory` returned null or threw anything else; no usable overload (reflection) |
| 25 | `NO_HOST` | No HELLO_HOST with our nonce within 10 s of the first HELLO |
| 26 | `LINK_LOST` | EOF, `EIO` or `ENODEV` on read or write, or no byte from the host for 4 s after the handshake |
| 27 | `LINK_STALLED` | One `write()` blocked for 4 s (§4.2) |
| 28 | `PROTOCOL` | Invalid header after the handshake, unknown channel, payload over the maximum |
| 29 | `BAD_OPTION` | `aoa` value malformed or combined with a rejected option (§1.2) |

`Server.main` maps an `AoaException(code, reason)` to its code; any other throwable stays exit 1.

---

## 2. Accessory acquisition on the phone (the accprobe approach, plus §2.4)

### 2.1 Order

1. Parse options. `SDK_INT < 33` -> exit 20.
2. `IUsbManager` through reflection:
   `ServiceManager.getService("usb")` -> `IUsbManager$Stub.asInterface(binder)`. A null binder is exit 24.
3. `getCurrentAccessory`, polled every 250 ms for up to 5 s (attach can lag the launch). Null -> exit 21.
4. Read the accessory serial (§2.3) and compare. Mismatch or unreadable -> exit 22.
5. `openAccessory(acc)`. `SecurityException` -> 23; null or other -> 24.
6. Hold exactly one `ParcelFileDescriptor` for the life of the process (single opener, F8). Wrap
   `pfd.getFileDescriptor()` in a plain `FileInputStream` and `FileOutputStream`. Never a
   `BufferedInputStream`, never `available()` (F9).
7. Log once at INFO: `aoa: accessory open (sdk <n>, <getCurrentAccessory signature>, <openAccessory signature>)`.

### 2.2 Method resolution (Android 13-16)

As `AccProbe.call`: among the public methods of the stub with that name whose parameters are all
`UsbAccessory`, `String` or `int`, take the one with the fewest parameters. Fill `UsbAccessory` with the
accessory, `String` with `"com.android.shell"`, `int` with `0`. On every build in §8.5 this resolves to
`UsbAccessory getCurrentAccessory()` and `ParcelFileDescriptor openAccessory(UsbAccessory)`; the
resolution stays so a vendor overload does not break the lane. Unwrap `InvocationTargetException`
before classifying the cause.

### 2.3 Identity and serial check

The provider sends these AOA strings at entry (§5.2):

| Index | String | Value |
|---|---|---|
| 0 | manufacturer | `Orka` |
| 1 | model | `OrkaLink` |
| 2 | description | `Orka video link` |
| 3 | version | `1` |
| 4 | URI | **never sent** (no "no app for this accessory" dialog) |
| 5 | serial | `orka-<scid>`, `<scid>` = `SCIDHex(SCID(adbSerial))`, e.g. `orka-0001e240` |

The serial is unique per phone, so it is also unique per port: one phone sits on a port at a time, and
the provider follows the port (§5.3). Deriving it from `scid` lets the server check it without another
command-line argument (the 290-character budget).

The server reads the serial as `AccProbe.accessorySerial`: the hidden field
`UsbAccessory.mSerialNumberReader`, then `IUsbSerialReader.getSerial("com.android.shell")` (works on
every build in §8.5; `UsbAccessory.getSerial()` throws under `app_process` because the caller package is
null). Fallback: `acc.getSerial()`. It must equal `"orka-" + String.format("%08x", scid)` exactly.

### 2.4 System-thread toggle

Steps 3-5 of §2.1 (waiting for, reading and opening the accessory) run with
`ActivityThread.currentActivityThread().mSystemThread` cleared through reflection, and restored in a
`finally`. Scrcpy's `Workarounds` mark the process's `ActivityThread` as a system thread, and
`UsbAccessory`'s constructor then requires a local `IUsbSerialReader` Stub, which is only true inside
system_server: unmarshalling the accessory throws `IllegalArgumentException` on every launch. accprobe
never hit this because it did not run under `Workarounds`. Removing the toggle sends every Android 13+
phone to lane D, silently. Fork `bc6cf662` (PR #16), `AccessoryOpener.open`.

---

## 3. Framing on the bulk pair

### 3.1 Sizes

- `MAX_IO` = 16384 bytes: one f_accessory transfer. A phone `read()` returns at most this and drops the
  rest of a larger transfer (F9).
- A message is header + payload, at most `MAX_IO` bytes, so the payload is at most **16376** bytes.
- Phone: every message is exactly one `write()` (so every write is <= 16 KiB).
- Host: every message is exactly one bulk OUT transfer; the host never puts two messages in one
  transfer. OUT transfers whose length is a multiple of the OUT endpoint's `wMaxPacketSize` carry
  `USBDEVFS_URB_ZERO_PACKET` (as `aoausb` `usbfsBulk.Write`; f_accessory requeues zero-length reads).
- Host IN: 8 transfers of 16384 bytes in flight (a multiple of 512 and 1024). IN is a byte stream:
  transfer boundaries mean nothing; zero-length completions are ignored.

### 3.2 Header (8 bytes, big-endian, both directions)

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 2 | magic | `0x4F4B` ("OK") |
| 2 | 1 | version | `1` (lane protocol version) |
| 3 | 1 | channel | `0` session, `1` video, `2` control (`3` reserved for audio) |
| 4 | 1 | type | channel 0: message type (§3.4); channels 1 and 2: `0` |
| 5 | 1 | flags | `0`; receivers ignore it |
| 6 | 2 | length | payload bytes, 0-16376 (channels 1 and 2: 1-16376) |

A header is valid when magic, version, channel (0-2) and length all check. Channel 3 is reserved
for audio and is invalid today. Before the handshake an
invalid header means "skip one byte and look again" (resync); after it, exit 28 on the phone and a
dead link on the host.

### 3.3 Handshake: the phone speaks first

1. The host posts its IN transfers **before** launching the server, and sends nothing until it has a
   valid HELLO.
2. Right after `openAccessory`, the phone sends HELLO, and again every 500 ms until it reads a
   HELLO_HOST whose nonce equals its own. It reads and discards everything before that HELLO_HOST
   (stale host bytes from a previous server, and HELLO_HOSTs with another nonce). No HELLO_HOST in 10 s
   -> exit 25.
3. The host discards IN bytes until a valid HELLO whose nonce equals the one it put in `aoa=` for
   this launch. Bytes before it are what an earlier server left queued (§8.5 4c: 3-4 messages each
   time) and are counted, not judged. On that HELLO it checks `serial == "orka-<scid>"`, then sends
   HELLO_HOST once. Repeated HELLOs with the same nonce are ignored. No such HELLO within 10 s of the
   launch -> launch failure (§5.7).
4. After HELLO_HOST, the phone starts the channel 1 and 2 pumps and the keepalive (§4). The host starts
   reading channels 1 and 2 at the first byte after the HELLO it answered.

### 3.4 Channel 0 messages

| Type | Name | Direction | Payload (big-endian) |
|---|---|---|---|
| `0x01` | HELLO | phone -> host | see below |
| `0x02` | HELLO_HOST | host -> phone | `u32 nonce` (echo) \| `u32 features` (0) |
| `0x03` | PING | both | `u32 seq` \| `u64 sender clock, ns` (opaque to the receiver) |
| `0x04` | PONG | both | the 12 PING bytes, echoed |
| `0x05` | BYE | both | `u8 reason` \| UTF-8 text, 0-255 bytes |

HELLO payload:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | nonce, u32, from `aoa=` |
| 4 | 2 | `Build.VERSION.SDK_INT`, u16 |
| 6 | 4 | pid, u32 (logs only; the provider kills by `scid`) |
| 10 | 4 | features, u32: bit 0 video on channel 1, bit 1 control on channel 2; other bits 0 |
| 14 | 1 + n | server version: u8 length, UTF-8 (`BuildConfig.VERSION_NAME`, `5.0.1`) |
| .. | 1 + n | accessory serial as read in §2.3: u8 length, UTF-8 |

BYE reasons: `0` stop (host asks the server to exit), `1` server exiting normally, `2` error (text says
which). A receiver ignores unknown channel 0 types; an unknown channel is a protocol error.

### 3.5 Channel 1: the video socket, unchanged

Channel 1 payloads, concatenated, are byte for byte what the forward-mode video socket carries today:
the dummy byte `0x00`, the 64-byte device name, the 4-byte codec id, then 12-byte session and media
packet headers with their data (orka-backend `provider/scrcpy/demuxer.go`). Message boundaries mean nothing; a
packet can span messages and a message can hold several packets. The provider's reader, demuxer and
`ReadCodecID` run unchanged on it.

### 3.6 Channel 2: the control socket, unchanged

Host -> phone: scrcpy control messages (`control_msg.go`), as a byte stream split into payloads of at
most 16376 bytes (a 256 KiB `SET_CLIPBOARD` spans messages). Phone -> host: scrcpy device messages
(clipboard, ACK, UHID). Two consequences, both from upstream behaviour with video in the same server:
- The dummy byte and device name go to channel 1 (the first socket). Channel 2 starts directly with
  device messages; the provider must **not** skip 1 + 64 bytes as the control-only session does.
- Positions in touch and scroll events must carry the current **video** size (the width and height of
  the latest channel 1 session packet), like the stock client. The server drops events made for
  another size (`Controller.getEventPointAndDisplayId`). The provider scales raw device pixels to that
  size.

`RESET_VIDEO` (keyframe request) goes on channel 2, as today.

---

## 4. Liveness and teardown

### 4.1 Constants

| Constant | Value | Side |
|---|---|---|
| HELLO resend | 500 ms | phone |
| Handshake timeout | 10 s | both (phone exit 25; host launch failure) |
| PING interval | 1 s, after the handshake | both; the receiver answers PONG at once |
| Silence timeout | 4 s without **any** received byte after the handshake | both |
| Blocked write | 4 s for one `write()` | phone, exit 27 |
| OUT transfer timeout | 2 s | host (phone not reading) |
| Stop grace | 1 s from host BYE to process exit | phone |

### 4.2 Phone side

- **Reader thread.** Always `read(buf, 0, 16384)` into a 16 KiB buffer, appended to a 32 KiB
  reassembly buffer, whole messages handled in order. Channel 2 payloads are written to the control
  pipe (§6.1). PING is answered with PONG. Any received byte updates "last host byte".
- **Writers.** One lock around the fd; each message is one `write()`. Senders: the video pump, the
  control pump, the keepalive, and the reader (PONG). Pumps read at most 16376 bytes from their pipe
  and send one message.
- **Watchdog thread** (every 250 ms): a `write()` blocked for 4 s -> exit 27 (f_accessory writes never
  time out when the host stops reading); no host byte for 4 s after the handshake -> exit 26.
- **EOF, `EIO`, `ENODEV`** on read or write -> exit 26.
- **Host BYE (reason 0)** -> exit 0 within 1 s.
- **Exit path, every case:** write the stderr line, close the `ParcelFileDescriptor` (this is what
  returns a reader blocked on an offline function; measured in the spike), then `System.exit(code)`
  from the watchdog. A blocked write cannot be interrupted from Java, so the process never waits for
  threads. No BYE is sent on a broken link; a best-effort BYE reason 2 is allowed when the link works.
- One process serves one host session. The server never resumes a session; the provider relaunches.

### 4.3 Host side

- **Link dead** when: no IN byte for 4 s after the handshake; an OUT transfer not done in 2 s; an IN or
  OUT transfer ends with `ENODEV`, `ESHUTDOWN`, `EPROTO` or `EPIPE`; or an invalid header after the
  handshake.
- On a dead link: stop the bulk pipe (discard all transfers), release the interface, kill the server by
  `scid` over adb if adb is up, then decide by the port (§5.5).
- PONGs give the RTT for logs; the host never needs them to stay alive (any IN byte counts, and the
  phone PINGs every second).
- A live link with no video is not a failure: a static or off screen sends nothing on channel 1 (the
  encoder's repeat-frame option is not honoured on every phone). The provider's video watchdog sends
  `RESET_VIDEO` on channel 2 after `StallTimeout` (10 s) without video; a live server answers with a
  session packet and a keyframe, so a static screen costs one probe about every 10 s. A probe left
  unanswered for `StallGrace` (5 s) is a stall at once when the link is dead or the launch never
  delivered an access unit (a stuck start, which counts towards §5.7). On a live link, once video has
  flowed, the watchdog probes again every `StallGrace` and ends the launch after 3 unanswered probes
  in a row (`StallTimeout` + 3 x `StallGrace`, 25 s by default): a running keepalive alone never
  keeps a frozen picture up.

### 4.4 Orderly stop

Host sends BYE reason 0, waits up to 1 s for the shell exit status, then kills by `scid` (unchanged
watchdog `pkill`), then stops the bulk pipe and releases the interface.

---

## 5. Provider lifecycle

### 5.1 Prerequisites

- `--android-transport=direct` (lane D's per-phone cell). There is no adb server, so the pilot's
  untargeted `adb reconnect` (trap T10) cannot reset a lane E phone.
- The provider flag `--android-video-lane=aoa` (default `adb`) enables lane E for every phone that
  qualifies; it refuses to start without `--android-transport=direct`.
- The udev rule of orka-backend `docs/android-direct-transport.md` also covers `18d1:2d00` and
  `18d1:2d01`: group `orka-adb` read-write and `power/control=on` (a new device after every entry). The
  rule file itself is in IaC `deploy/dev-server/host/` (IaC Spec 003).
- `usbfs_memory_mb` = 0 (unlimited) or >= 1024: each lane E phone holds 8 x 16 KiB IN transfers plus its
  adb transfers. The provider logs a warning at start when the value is lower.

### 5.2 Eligibility and entry

1. The cell is online in normal mode. Read `ro.build.version.sdk`, `ro.build.fingerprint` and
   `sys.usb.config` over adb.
2. Lane D if `sdk < 33`, or if the phone is demoted (§5.7). Lane D never touches USB functions.
3. If the port already shows `18d1:2d01` (provider restart, phone left in accessory mode): **adopt**.
   No entry; the previous config is read from `persist.sys.usb.config`. Go to 5.
4. **Entry**, holding a host-wide entry lock (one phone between START and "adb back" at a time) and a
   per-port `flock` on `/run/orka/aoa-<port path>.lock` (one owner per port across processes):
   - record the port path (sysfs name, e.g. `7-1.1.3.1`) and devnum from the cell's node;
   - on that `04e8:*` node, no interface claimed: GET_PROTOCOL (51, `0xC0`, need >= 1), SEND_STRING
     (52, `0x40`) for indexes 0, 1, 2, 3, 5 with 10 ms gaps, no URI, START (53, `0x40`); the sequence and
     its checks are `aoausb.AOASequence` and `RunAOASwitch`;
   - never reset and never `SET_CONFIGURATION` after START;
   - wait for the **same port path** to show `18d1:2d01` with a new devnum within 5 s, with the same
     `serial` attribute as the adb serial (true on every build in §8.5). `2d00` means USB debugging is
     off: entry failure. Measured: about 0.7 s.
   - wait for the cell to be online again within 10 s (measured: about 1 s), rescanning sysfs on
     every poll of that wait: the kernel shows the `2d01` device before its interfaces, so one scan
     right after the port changes can miss the adb interface. The cell keeps finding the phone by
     serial; the AOA lane checks that its node sits on the recorded port path.
5. Push the jar if the phone's SHA-256 differs (`DeviceJarHashCommand`, unchanged). Start the other
   adb-launched processes only now: the entry is a function change that killed every adb-launched
   process on the phone (F5, F6), so nothing the provider needs may be launched before it.

### 5.3 Claim and launch

1. Open the `2d01` node with its own usbfs fd, parse the active configuration, claim **only** the
   interface `ff/ff/00` (never `ff/42/01`, never assume interface 0), post 8 IN transfers of 16 KiB.
2. Kill a leftover server by `scid` (single opener), then launch the §1.1 command with a fresh nonce
   through the cell's shell (`Shell.Start`, as lane D).
3. Handshake (§3.3), then hand channel 1 to the video reader and channel 2 to the control session.

Seams: the AOA link implements `videostream.DeviceDialer` for `Service(scid)`. The first `Dial` of a
launch returns channel 1 (its first byte is the dummy byte, as the forward-mode connect loop expects),
the second returns channel 2, any other service is an error. `scrcpy.Source` runs on it unchanged
except for the command line. In lane E the control-only server (Stage 2b) is **not** launched; the
`Controller` sends on the Source's channel 2 and reads device messages from it (§3.6).

### 5.4 Relaunch without re-entry

When the server exits or the link dies but the port still shows `2d01` with the **same devnum**: keep
or reclaim the interface, kill by `scid`, launch with a new nonce. Stale IN bytes are dropped by the
nonce rule. Measured: p95 0.41 s (§8.5 4c). Backoff between relaunches as lane D's Broadcaster.

### 5.5 After a flap or a function change

| Port shows | Meaning | Action |
|---|---|---|
| `2d01`, same devnum | Server or link died, accessory mode kept | §5.4 |
| `04e8:*` (any devnum) | Accessory mode ended (flap, function change, reboot); adbd restarted and killed the server | Full re-entry (§5.2 step 4), then §5.3. Measured: flap p95 3.0 s, function change p95 2.4 s |
| `2d01`, new devnum | Re-enumerated in accessory mode | §5.3 (new claim) |
| nothing | Unplugged | Wait for the cell; when the phone is back, start at §5.2 |

A full re-entry after a flap is a recovery, not a failure, unless it fails itself.

### 5.6 Leaving accessory mode (orderly shutdown, demotion)

1. Stop lane E (§4.4).
2. If less than 11 s since START, wait until 11 s (the accessory window, F7).
3. Over adb on the composite: `svc usb setFunctions <fn>`, with `<fn>` the first of `mtp`, `ptp`,
   `rndis`, `midi` found in the recorded `sys.usb.config` (or `persist.sys.usb.config` when adopted), or
   nothing (charging) when none is there (`aoausb.ExitCommandFor`). adbd restarts, so the command may
   end without a status; bound it to 10 s and ignore its error.
4. Wait up to 10 s for a device that is not `18d1:2d0x` at the port path. If the wait is cut short
   (the device was reset meanwhile), stop here without a reset: the function change may still be
   under way, and the next Setup reads the port.
5. Still in accessory mode: one `USBDEVFS_RESET` on **that device's node only**. `ENODEV` from the ioctl
   means done (the phone left the bus during it). Wait 10 s more.
6. Still in accessory mode: log it and leave it; the next provider start adopts it (§5.2 step 3).

On shutdown, entries stop first (a device reset during the shutdown gets lane D), then every phone
the provider entered or adopted that is still in accessory mode leaves in parallel, whether or not a
Setup holds its lane at that moment. A provider that dies without shutdown leaves its phones in
accessory mode, and the next start adopts them.

### 5.7 Fallback to lane D

| Event | Action |
|---|---|
| `sdk < 33` (Android 12) | Lane D, never switched |
| Exit 23 `PERMISSION_DENIED`, or 20 | Leave accessory mode, lane D for that phone until its `ro.build.fingerprint` changes |
| Exit 29 `BAD_OPTION` | Provider bug: log an error, leave accessory mode, lane D until restart |
| 3 failures within 10 min with no healthy session in between | Leave accessory mode, lane D; retry AOA after 30 min, then 2 h, then every 8 h, at the device's first Setup after that time (no device reset just to retry) |

A **failure** is an entry that does not reach "adb back" (no `2d01` in 5 s, `2d00`, GET_PROTOCOL < 1, adb
not back in 10 s; not when the entry was cut short by the device's own reset), a launch that cannot
claim `ff/ff/00` or post its IN transfers (`EBUSY`, `ENOMEM`; not a node that vanished), a launch
that does not reach the first channel 1 media packet (exit 21, 22, 24, 25,
1, no HELLO in 10 s), or a session that dies with exit 26, 27, 28 or a dead link within 60 s of its
first media packet. A session that streams for 60 s resets the count. On exit 21 or 22 the next attempt
is a full re-entry (leave, then enter); on 24 a relaunch.

State is in memory only (POC): after a provider restart, each eligible phone gets one fresh attempt.

### 5.8 What stays on adb

IME, files, installed apps, screenshots, rotation, unlock and the Stage 2b REST fallbacks keep using
adb through the direct cell, now on the composite's `ff/42/01` interface. Their code does not change;
the processes the entry killed are relaunched by their existing supervisors. Lane E only adds the
`ff/ff/00` claim on the same device.

---

## 6. Phone-side structure (fork) and versioning

### 6.1 Fork shape

Branch `feature/aoa-transport` from `develop` in `Research-Warrant/scrcpy`, conventional commits
(`feat(server): ...`). Changes are additive and only reached with `aoa`:
- new package `com.genymobile.scrcpy.aoa`: `AccessoryOpener` (§2), `AccessoryLink` (§3, §4: framing,
  reader, write lock, keepalive, watchdog), `AoaException` (code + reason);
- `Options`: parse and validate `aoa` (§1.2);
- `DesktopConnection.openAccessory(...)`: opens the link, completes the handshake, creates one
  `Os.socketpair(AF_UNIX, SOCK_STREAM)` for video and one for control; the `Streamer` writes to the
  video pair's fd and `ControlChannel` (new constructor from an `InputStream`/`OutputStream`) uses the
  control pair, so the encoder, `Streamer` and `Controller` are untouched; the link pumps the other ends;
- `Server`: the `aoa` branch and the exit-code mapping.

Package and class names stay upstream's (`com.genymobile.scrcpy.Server`): renaming would touch every
file and every upstream merge, and the provider kills by `scid`, not by class. Modified files carry a
"Modified by Research Warrant" notice line (Apache-2.0 §4(b)).

### 6.2 Version string and capability

- `versionName` stays **`5.0.1`**. The server refuses any other version argument, and the provider's
  `ServerVersion` stays `5.0.1`, so the command line and `KillCommand` do not change.
- The provider knows the jar supports `aoa` from the **jar's SHA-256**, not from a version string: it
  embeds one jar, checks it with `VerifyServerJar` at start, and re-pushes when the phone's
  `sha256sum` differs. The lane protocol version is the header's `version` byte (1). Any wire change
  bumps it and ships a new jar (new SHA-256) in the same provider change.
- An upstream jar given `aoa=` only warns "Unknown server option", then fails to connect its reverse
  socket and exits 1 at once: a failure, never a hang.

### 6.3 Shipping the jar

- orka-backend embeds the fork jar **instead of** the upstream release, at the same device path
  (`/data/local/tmp/orka-scrcpy.jar`). Lane D and lane E run the same file.
- Resource name `scrcpy-server-v5.0.1-orka.1` in orka-backend `resources/`; `ServerResource` and
  `ServerSHA256` in orka-backend `provider/scrcpy/jar.go` change to it.
- Built from a recorded fork commit with the fork's `server/build_without_gradle.sh` (android-36,
  build-tools 36.0.0, JDK 17): a dex-only zip, not the Gradle `release/build_server.sh` APK, which
  gives other bytes from the same commit. The fork commit hash goes in the `jar.go` comment and in
  the NOTICE. The pinned build is fork merge `7ef1607c` (PR #16, the same server tree as `bc6cf662`,
  on master since PR #17), 108861 bytes, SHA-256
  `8c3255380f76e8756531c82a1954c0c57ab4601324cf433d937bc894b79585a3`. The release asset is
  scrcpy-fork Spec 002; the embed and the pin are orka-backend Spec 0023.
- Licence files next to it: orka-backend `resources/<resource>.LICENSE` (the fork's Apache-2.0
  `LICENSE`, verbatim) and orka-backend `resources/<resource>.NOTICE` (upstream copyright, fork
  repository and commit, build, one paragraph on what changed).
- When the owner publishes the fork release (`v5.0.1-orka.1`, asset `scrcpy-server-v5.0.1-orka.1`),
  the asset is that same `build_without_gradle.sh` file, so the embedded file and `ServerSHA256` stay
  as they are. A build that gives other bytes (Gradle, another toolchain) is a new file: replace the
  embedded file and change `ServerSHA256`. The provider trusts the hash, not where the file came
  from; no test pins the size.

---

## 7. Tests both sides need

- Fork (`server/src/test/java/com/genymobile/scrcpy/`): header encode and parse, valid, resync before
  the handshake and rejection after (`aoa/AoaProtocolTest`, `aoa/MessageParserTest`); the Appendix A
  vectors (`aoa/AoaContractVectorsTest`); channels 0-3, length bounds, unknown channel 0 types and
  channel 2 split and reassembly (`aoa/AoaChannelFramingTest`); the §4.1 timings and the §1.3 accessory
  wait (`aoa/AoaLivenessConstantsTest`); the nonce rule, option validation (exit 29 cases) and the exit
  code table (`aoa/AoaProtocolTest`, `OptionsAoaTest`, `aoa/AoaExitCodesTest`); the server without
  `aoa` on the provider's lane D command lines (`OptionsUpstreamParityTest`). The `Server.main` mapping
  itself is checked on a phone (owner-run).
- Provider (orka-backend, Spec 0033): the Appendix A vectors (`provider/aoalane/link_test.go`,
  `TestHeaderAndHelloMatchTheContractVectors` and `TestWireMatchesTheForkVectors`); stale-byte discard,
  channel split and reassembly across message boundaries and the `Dial` order (`link_test.go`); the
  fallback table (§5.7) with a fake clock (`policy_test.go`); the entry and port locks
  (`lock_linux_test.go`); `ShellCommand` lane E cases under 290.
- On a phone (owner-run): S3-style stream with the real server at 1 and 8 Mbit/s, a 4c and a 4d cycle,
  control taps landing at the right pixel through channel 2, and an Android 12 phone falling to lane D.
  "Android 13+ phones open accessory links with no fallback to lane D" is the regression check for
  §2.4.

---

## Appendix A. Test vectors

Both sides assert every row: the fork in `aoa/AoaContractVectorsTest`, orka-backend in
`TestWireMatchesTheForkVectors` (`provider/aoalane/link_test.go`). Hex bytes, big-endian.

| Row | Input | Bytes |
|---|---|---|
| Video message | channel 1, type 0, payload `aa` | `4f4b0101 00000001 aa` |
| HELLO_HOST | nonce `0x01020304`, features 0 | `4f4b0100 02000008 01020304 00000000` |
| HELLO_HOST match | the HELLO_HOST above, phone nonce `0x01020304` | `isHelloHostFor` is true |
| HELLO | nonce `0x01020304`, sdk 34, pid `0x0a0b0c0d`, features 3, version `5.0.1`, serial `orka-0001e240` | header `4f4b0100 01000022`, payload `01020304 0022 0a0b0c0d 00000003 05 "5.0.1" 0d "orka-0001e240"` |
| PONG header | channel 0, type PONG, length 12 | `4f4b0100 0400000c` |
| BYE | reason 0 (stop), text `x` | `4f4b0100 05000002 0078` |
| `MAX_IO` | | 16384 |
| `HEADER_LENGTH` | | 8 |
| `MAX_PAYLOAD` | `MAX_IO` - `HEADER_LENGTH` | 16376 |
