#include "control_msg.h"

#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "util/binary.h"
#include "util/log.h"
#include "util/str.h"

/**
 * Map an enum value to a string based on an array, without crashing on an
 * out-of-bounds index.
 */
#define ENUM_TO_LABEL(labels, value) \
    ((size_t) (value) < ARRAY_LEN(labels) ? labels[value] : "???")

#define KEYEVENT_ACTION_LABEL(value) \
    ENUM_TO_LABEL(android_keyevent_action_labels, value)

#define MOTIONEVENT_ACTION_LABEL(value) \
    ENUM_TO_LABEL(android_motionevent_action_labels, value)

static const char *const android_keyevent_action_labels[] = {
    "down",
    "up",
    "multi",
};

static const char *const android_motionevent_action_labels[] = {
    "down",
    "up",
    "move",
    "cancel",
    "outside",
    "pointer-down",
    "pointer-up",
    "hover-move",
    "scroll",
    "hover-enter",
    "hover-exit",
    "btn-press",
    "btn-release",
};

static const char *const copy_key_labels[] = {
    "none",
    "copy",
    "cut",
};

static inline const char *
get_well_known_pointer_id_name(uint64_t pointer_id) {
    switch (pointer_id) {
        case SC_POINTER_ID_MOUSE:
            return "mouse";
        case SC_POINTER_ID_GENERIC_FINGER:
            return "finger";
        case SC_POINTER_ID_VIRTUAL_FINGER:
            return "vfinger";
        default:
            return NULL;
    }
}

static inline bool
is_mouse_pointer(uint64_t pointer_id) {
    return pointer_id == SC_POINTER_ID_MOUSE;
}

static inline bool
is_finger_pointer(uint64_t pointer_id) {
    return pointer_id == SC_POINTER_ID_GENERIC_FINGER
        || pointer_id == SC_POINTER_ID_VIRTUAL_FINGER;
}

static const char *
motionevent_action_label_human(enum android_motionevent_action action) {
    static const char *const labels[] = {
        [AMOTION_EVENT_ACTION_DOWN] = "Down",
        [AMOTION_EVENT_ACTION_UP] = "Up",
        [AMOTION_EVENT_ACTION_MOVE] = "Move",
        [AMOTION_EVENT_ACTION_CANCEL] = "Cancel",
        [AMOTION_EVENT_ACTION_OUTSIDE] = "Outside",
        [AMOTION_EVENT_ACTION_POINTER_DOWN] = "PointerDown",
        [AMOTION_EVENT_ACTION_POINTER_UP] = "PointerUp",
        [AMOTION_EVENT_ACTION_HOVER_MOVE] = "Hover",
        [AMOTION_EVENT_ACTION_SCROLL] = "Scroll",
        [AMOTION_EVENT_ACTION_HOVER_ENTER] = "HoverEnter",
        [AMOTION_EVENT_ACTION_HOVER_EXIT] = "HoverExit",
        [AMOTION_EVENT_ACTION_BUTTON_PRESS] = "ButtonPress",
        [AMOTION_EVENT_ACTION_BUTTON_RELEASE] = "ButtonRelease",
    };

    return ENUM_TO_LABEL(labels, action);
}

static const char *
pointer_target_name(uint64_t pointer_id) {
    switch (pointer_id) {
        case SC_POINTER_ID_GENERIC_FINGER:
            return "finger";
        case SC_POINTER_ID_VIRTUAL_FINGER:
            return "virtualFinger";
        case SC_POINTER_ID_MOUSE:
            return "mouse";
        default:
            return "pointer";
    }
}

static const char *
mouse_button_name(enum android_motionevent_buttons button) {
    switch (button) {
        case AMOTION_EVENT_BUTTON_PRIMARY:
            return "left";
        case AMOTION_EVENT_BUTTON_SECONDARY:
            return "right";
        case AMOTION_EVENT_BUTTON_TERTIARY:
            return "middle";
        case AMOTION_EVENT_BUTTON_BACK:
            return "back";
        case AMOTION_EVENT_BUTTON_FORWARD:
            return "forward";
        default:
            return NULL;
    }
}

static const char *
buttons_to_string(enum android_motionevent_buttons buttons, char buf[32]) {
    if (!buttons) {
        return "none";
    }

    static const struct {
        enum android_motionevent_buttons button;
        const char *name;
    } values[] = {
        { AMOTION_EVENT_BUTTON_PRIMARY, "left" },
        { AMOTION_EVENT_BUTTON_SECONDARY, "right" },
        { AMOTION_EVENT_BUTTON_TERTIARY, "middle" },
        { AMOTION_EVENT_BUTTON_BACK, "back" },
        { AMOTION_EVENT_BUTTON_FORWARD, "forward" },
    };

    enum android_motionevent_buttons known = 0;
    for (size_t i = 0; i < ARRAY_LEN(values); ++i) {
        known |= values[i].button;
    }

    if (buttons & ~known) {
        snprintf(buf, 32, "0x%08lx", (long) buttons);
        return buf;
    }

    size_t len = 0;
    for (size_t i = 0; i < ARRAY_LEN(values); ++i) {
        if (!(buttons & values[i].button)) {
            continue;
        }
        if (len) {
            buf[len++] = '|';
        }
        size_t name_len = strlen(values[i].name);
        memcpy(&buf[len], values[i].name, name_len);
        len += name_len;
    }
    buf[len] = '\0';

    return buf;
}

static void
write_position(uint8_t *buf, const struct sc_position *position) {
    sc_write32be(&buf[0], position->point.x);
    sc_write32be(&buf[4], position->point.y);
    sc_write16be(&buf[8], position->screen_size.width);
    sc_write16be(&buf[10], position->screen_size.height);
}

// Write truncated string, and return the size
static size_t
write_string_payload(uint8_t *payload, const char *utf8, size_t max_len) {
    if (!utf8) {
        return 0;
    }
    size_t len = sc_str_utf8_truncation_index(utf8, max_len);
    memcpy(payload, utf8, len);
    return len;
}

// Write length (4 bytes) + string (non null-terminated)
static size_t
write_string(uint8_t *buf, const char *utf8, size_t max_len) {
    size_t len = write_string_payload(buf + 4, utf8, max_len);
    sc_write32be(buf, len);
    return 4 + len;
}

// Write length (1 byte) + string (non null-terminated)
static size_t
write_string_tiny(uint8_t *buf, const char *utf8, size_t max_len) {
    assert(max_len <= 0xFF);
    size_t len = write_string_payload(buf + 1, utf8, max_len);
    buf[0] = len;
    return 1 + len;
}

size_t
sc_control_msg_serialize(const struct sc_control_msg *msg, uint8_t *buf) {
    buf[0] = msg->type;
    switch (msg->type) {
        case SC_CONTROL_MSG_TYPE_INJECT_KEYCODE:
            buf[1] = msg->inject_keycode.action;
            sc_write32be(&buf[2], msg->inject_keycode.keycode);
            sc_write32be(&buf[6], msg->inject_keycode.repeat);
            sc_write32be(&buf[10], msg->inject_keycode.metastate);
            return 14;
        case SC_CONTROL_MSG_TYPE_INJECT_TEXT: {
            size_t len = write_string(&buf[1], msg->inject_text.text,
                                      SC_CONTROL_MSG_INJECT_TEXT_MAX_LENGTH);
            return 1 + len;
        }
        case SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT:
            buf[1] = msg->inject_touch_event.action;
            sc_write64be(&buf[2], msg->inject_touch_event.pointer_id);
            write_position(&buf[10], &msg->inject_touch_event.position);
            uint16_t pressure =
                sc_float_to_u16fp(msg->inject_touch_event.pressure);
            sc_write16be(&buf[22], pressure);
            sc_write32be(&buf[24], msg->inject_touch_event.action_button);
            sc_write32be(&buf[28], msg->inject_touch_event.buttons);
            return 32;
        case SC_CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT:
            write_position(&buf[1], &msg->inject_scroll_event.position);
            // Accept values in the range [-16, 16].
            // Normalize to [-1, 1] in order to use sc_float_to_i16fp().
            float hscroll_norm = msg->inject_scroll_event.hscroll / 16;
            hscroll_norm = CLAMP(hscroll_norm, -1, 1);
            float vscroll_norm = msg->inject_scroll_event.vscroll / 16;
            vscroll_norm = CLAMP(vscroll_norm, -1, 1);
            int16_t hscroll = sc_float_to_i16fp(hscroll_norm);
            int16_t vscroll = sc_float_to_i16fp(vscroll_norm);
            sc_write16be(&buf[13], (uint16_t) hscroll);
            sc_write16be(&buf[15], (uint16_t) vscroll);
            sc_write32be(&buf[17], msg->inject_scroll_event.buttons);
            return 21;
        case SC_CONTROL_MSG_TYPE_BACK_OR_SCREEN_ON:
            buf[1] = msg->inject_keycode.action;
            return 2;
        case SC_CONTROL_MSG_TYPE_GET_CLIPBOARD:
            buf[1] = msg->get_clipboard.copy_key;
            return 2;
        case SC_CONTROL_MSG_TYPE_SET_CLIPBOARD:
            sc_write64be(&buf[1], msg->set_clipboard.sequence);
            buf[9] = !!msg->set_clipboard.paste;
            size_t len = write_string(&buf[10], msg->set_clipboard.text,
                                      SC_CONTROL_MSG_CLIPBOARD_TEXT_MAX_LENGTH);
            return 10 + len;
        case SC_CONTROL_MSG_TYPE_SET_DISPLAY_POWER:
            buf[1] = msg->set_display_power.on;
            return 2;
        case SC_CONTROL_MSG_TYPE_UHID_CREATE:
            sc_write16be(&buf[1], msg->uhid_create.id);
            sc_write16be(&buf[3], msg->uhid_create.vendor_id);
            sc_write16be(&buf[5], msg->uhid_create.product_id);

            size_t index = 7;
            index += write_string_tiny(&buf[index], msg->uhid_create.name, 127);

            sc_write16be(&buf[index], msg->uhid_create.report_desc_size);
            index += 2;

            memcpy(&buf[index], msg->uhid_create.report_desc,
                                msg->uhid_create.report_desc_size);
            index += msg->uhid_create.report_desc_size;

            return index;
        case SC_CONTROL_MSG_TYPE_UHID_INPUT:
            sc_write16be(&buf[1], msg->uhid_input.id);
            sc_write16be(&buf[3], msg->uhid_input.size);
            memcpy(&buf[5], msg->uhid_input.data, msg->uhid_input.size);
            return 5 + msg->uhid_input.size;
        case SC_CONTROL_MSG_TYPE_UHID_DESTROY:
            sc_write16be(&buf[1], msg->uhid_destroy.id);
            return 3;
        case SC_CONTROL_MSG_TYPE_START_APP: {
            size_t len = write_string_tiny(&buf[1], msg->start_app.name, 255);
            return 1 + len;
        }
        case SC_CONTROL_MSG_TYPE_EXPAND_NOTIFICATION_PANEL:
        case SC_CONTROL_MSG_TYPE_EXPAND_SETTINGS_PANEL:
        case SC_CONTROL_MSG_TYPE_COLLAPSE_PANELS:
        case SC_CONTROL_MSG_TYPE_ROTATE_DEVICE:
        case SC_CONTROL_MSG_TYPE_OPEN_HARD_KEYBOARD_SETTINGS:
        case SC_CONTROL_MSG_TYPE_RESET_VIDEO:
            // no additional data
            return 1;
        default:
            LOGW("Unknown message type: %u", (unsigned) msg->type);
            return 0;
    }
}

void
sc_control_msg_log(const struct sc_control_msg *msg) {
#define LOG_CMSG(fmt, ...) LOGV("input: " fmt, ## __VA_ARGS__)
    switch (msg->type) {
        case SC_CONTROL_MSG_TYPE_INJECT_KEYCODE:
            LOG_CMSG("key %-4s code=%d repeat=%" PRIu32 " meta=%06lx",
                     KEYEVENT_ACTION_LABEL(msg->inject_keycode.action),
                     (int) msg->inject_keycode.keycode,
                     msg->inject_keycode.repeat,
                     (long) msg->inject_keycode.metastate);
            break;
        case SC_CONTROL_MSG_TYPE_INJECT_TEXT:
            LOG_CMSG("text \"%s\"", msg->inject_text.text);
            break;
        case SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT: {
            int action = msg->inject_touch_event.action
                       & AMOTION_EVENT_ACTION_MASK;
            uint64_t id = msg->inject_touch_event.pointer_id;
            const char *pointer_name = get_well_known_pointer_id_name(id);
            if (pointer_name) {
                // string pointer id
                LOG_CMSG("touch [id=%s] %-4s position=%" PRIi32 ",%" PRIi32
                             " pressure=%f action_button=%06lx buttons=%06lx",
                         pointer_name,
                         MOTIONEVENT_ACTION_LABEL(action),
                         msg->inject_touch_event.position.point.x,
                         msg->inject_touch_event.position.point.y,
                         msg->inject_touch_event.pressure,
                         (long) msg->inject_touch_event.action_button,
                         (long) msg->inject_touch_event.buttons);
            } else {
                // numeric pointer id
                LOG_CMSG("touch [id=%" PRIu64_ "] %-4s position=%" PRIi32 ",%"
                             PRIi32 " pressure=%f action_button=%06lx"
                             " buttons=%06lx",
                         id,
                         MOTIONEVENT_ACTION_LABEL(action),
                         msg->inject_touch_event.position.point.x,
                         msg->inject_touch_event.position.point.y,
                         msg->inject_touch_event.pressure,
                         (long) msg->inject_touch_event.action_button,
                         (long) msg->inject_touch_event.buttons);
            }
            break;
        }
        case SC_CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT:
            LOG_CMSG("scroll position=%" PRIi32 ",%" PRIi32 " hscroll=%f"
                         " vscroll=%f buttons=%06lx",
                     msg->inject_scroll_event.position.point.x,
                     msg->inject_scroll_event.position.point.y,
                     msg->inject_scroll_event.hscroll,
                     msg->inject_scroll_event.vscroll,
                     (long) msg->inject_scroll_event.buttons);
            break;
        case SC_CONTROL_MSG_TYPE_BACK_OR_SCREEN_ON:
            LOG_CMSG("back-or-screen-on %s",
                     KEYEVENT_ACTION_LABEL(msg->inject_keycode.action));
            break;
        case SC_CONTROL_MSG_TYPE_GET_CLIPBOARD:
            LOG_CMSG("get clipboard copy_key=%s",
                     copy_key_labels[msg->get_clipboard.copy_key]);
            break;
        case SC_CONTROL_MSG_TYPE_SET_CLIPBOARD:
            LOG_CMSG("clipboard %" PRIu64_ " %s \"%s\"",
                     msg->set_clipboard.sequence,
                     msg->set_clipboard.paste ? "paste" : "nopaste",
                     msg->set_clipboard.text);
            break;
        case SC_CONTROL_MSG_TYPE_SET_DISPLAY_POWER:
            LOG_CMSG("display power %s",
                     msg->set_display_power.on ? "on" : "off");
            break;
        case SC_CONTROL_MSG_TYPE_EXPAND_NOTIFICATION_PANEL:
            LOG_CMSG("expand notification panel");
            break;
        case SC_CONTROL_MSG_TYPE_EXPAND_SETTINGS_PANEL:
            LOG_CMSG("expand settings panel");
            break;
        case SC_CONTROL_MSG_TYPE_COLLAPSE_PANELS:
            LOG_CMSG("collapse panels");
            break;
        case SC_CONTROL_MSG_TYPE_ROTATE_DEVICE:
            LOG_CMSG("rotate device");
            break;
        case SC_CONTROL_MSG_TYPE_UHID_CREATE: {
            // Quote only if name is not null
            const char *name = msg->uhid_create.name;
            const char *quote = name ? "\"" : "";
            LOG_CMSG("UHID create [%" PRIu16 "] %04" PRIx16 ":%04" PRIx16
                     " name=%s%s%s report_desc_size=%" PRIu16,
                     msg->uhid_create.id,
                     msg->uhid_create.vendor_id,
                     msg->uhid_create.product_id,
                     quote, name, quote,
                     msg->uhid_create.report_desc_size);
            break;
        }
        case SC_CONTROL_MSG_TYPE_UHID_INPUT: {
            char *hex = sc_str_to_hex_string(msg->uhid_input.data,
                                             msg->uhid_input.size);
            if (hex) {
                LOG_CMSG("UHID input [%" PRIu16 "] %s",
                         msg->uhid_input.id, hex);
                free(hex);
            } else {
                LOG_CMSG("UHID input [%" PRIu16 "] size=%" PRIu16,
                         msg->uhid_input.id, msg->uhid_input.size);
            }
            break;
        }
        case SC_CONTROL_MSG_TYPE_UHID_DESTROY:
            LOG_CMSG("UHID destroy [%" PRIu16 "]", msg->uhid_destroy.id);
            break;
        case SC_CONTROL_MSG_TYPE_OPEN_HARD_KEYBOARD_SETTINGS:
            LOG_CMSG("open hard keyboard settings");
            break;
        case SC_CONTROL_MSG_TYPE_START_APP:
            LOG_CMSG("start app \"%s\"", msg->start_app.name);
            break;
        case SC_CONTROL_MSG_TYPE_RESET_VIDEO:
            LOG_CMSG("reset video");
            break;
        default:
            LOG_CMSG("unknown type: %u", (unsigned) msg->type);
            break;
    }
#undef LOG_CMSG
}

void
sc_control_msg_log_human(const struct sc_control_msg *msg) {
#define LOG_CMSG(fmt, ...) LOGI_USER_ACTION("send: " fmt, ## __VA_ARGS__)
    switch (msg->type) {
        case SC_CONTROL_MSG_TYPE_INJECT_KEYCODE:
            switch (msg->inject_keycode.action) {
                case AKEY_EVENT_ACTION_DOWN:
                    LOG_CMSG("keyDown code=%d",
                             (int) msg->inject_keycode.keycode);
                    break;
                case AKEY_EVENT_ACTION_UP:
                    LOG_CMSG("keyUp code=%d",
                             (int) msg->inject_keycode.keycode);
                    break;
                case AKEY_EVENT_ACTION_MULTIPLE:
                    LOG_CMSG("keyMulti code=%d repeat=%" PRIu32,
                             (int) msg->inject_keycode.keycode,
                             msg->inject_keycode.repeat);
                    break;
                default:
                    LOG_CMSG("unknownType=%u", (unsigned) msg->type);
                    break;
            }
            break;
        case SC_CONTROL_MSG_TYPE_INJECT_TEXT:
            LOG_CMSG("text \"%s\"", msg->inject_text.text);
            break;
        case SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT: {
            enum android_motionevent_action action =
                msg->inject_touch_event.action & AMOTION_EVENT_ACTION_MASK;
            const struct sc_point *point = &msg->inject_touch_event.position.point;
            uint64_t pointer_id = msg->inject_touch_event.pointer_id;

            if (is_mouse_pointer(pointer_id)) {
                char buttons[32];
                const char *button_name =
                    mouse_button_name(msg->inject_touch_event.action_button);
                switch (action) {
                    case AMOTION_EVENT_ACTION_HOVER_MOVE:
                        LOG_CMSG("mouseHover at (%" PRIi32 ", %" PRIi32 ")",
                                 point->x, point->y);
                        break;
                    case AMOTION_EVENT_ACTION_MOVE:
                        if (msg->inject_touch_event.buttons) {
                            LOG_CMSG("mouseDrag at (%" PRIi32 ", %" PRIi32
                                     ") buttons=%s",
                                     point->x, point->y,
                                     buttons_to_string(
                                         msg->inject_touch_event.buttons,
                                         buttons));
                        } else {
                            LOG_CMSG("mouseMove at (%" PRIi32 ", %" PRIi32 ")",
                                     point->x, point->y);
                        }
                        break;
                    case AMOTION_EVENT_ACTION_DOWN:
                        if (button_name) {
                            LOG_CMSG("%sClickDown at (%" PRIi32 ", %" PRIi32
                                     ")",
                                     button_name, point->x, point->y);
                        } else {
                            LOG_CMSG("mouse%s at (%" PRIi32 ", %" PRIi32
                                     ") buttons=%s",
                                     motionevent_action_label_human(action),
                                     point->x, point->y,
                                     buttons_to_string(
                                         msg->inject_touch_event.buttons,
                                         buttons));
                        }
                        break;
                    case AMOTION_EVENT_ACTION_UP:
                        if (button_name) {
                            LOG_CMSG("%sClickUp at (%" PRIi32 ", %" PRIi32 ")",
                                     button_name, point->x, point->y);
                        } else {
                            LOG_CMSG("mouse%s at (%" PRIi32 ", %" PRIi32
                                     ") buttons=%s",
                                     motionevent_action_label_human(action),
                                     point->x, point->y,
                                     buttons_to_string(
                                         msg->inject_touch_event.buttons,
                                         buttons));
                        }
                        break;
                    case AMOTION_EVENT_ACTION_BUTTON_PRESS:
                        if (button_name) {
                            LOG_CMSG("%sButtonPress at (%" PRIi32 ", %" PRIi32
                                     ")",
                                     button_name, point->x, point->y);
                        } else {
                            LOG_CMSG("mouse%s at (%" PRIi32 ", %" PRIi32
                                     ") buttons=%s",
                                     motionevent_action_label_human(action),
                                     point->x, point->y,
                                     buttons_to_string(
                                         msg->inject_touch_event.buttons,
                                         buttons));
                        }
                        break;
                    case AMOTION_EVENT_ACTION_BUTTON_RELEASE:
                        if (button_name) {
                            LOG_CMSG("%sButtonRelease at (%" PRIi32 ", %" PRIi32
                                     ")",
                                     button_name, point->x, point->y);
                        } else {
                            LOG_CMSG("mouse%s at (%" PRIi32 ", %" PRIi32
                                     ") buttons=%s",
                                     motionevent_action_label_human(action),
                                     point->x, point->y,
                                     buttons_to_string(
                                         msg->inject_touch_event.buttons,
                                         buttons));
                        }
                        break;
                    default:
                        LOG_CMSG("mouse%s at (%" PRIi32 ", %" PRIi32
                                 ") buttons=%s",
                                 motionevent_action_label_human(action),
                                 point->x, point->y,
                                 buttons_to_string(
                                     msg->inject_touch_event.buttons, buttons));
                        break;
                }
            } else if (is_finger_pointer(pointer_id)) {
                const char *target = pointer_target_name(pointer_id);
                switch (action) {
                    case AMOTION_EVENT_ACTION_DOWN:
                        LOG_CMSG("%sPress at (%" PRIi32 ", %" PRIi32
                                 ") pressure=%f",
                                 target, point->x, point->y,
                                 msg->inject_touch_event.pressure);
                        break;
                    case AMOTION_EVENT_ACTION_UP:
                        LOG_CMSG("%sRelease at (%" PRIi32 ", %" PRIi32
                                 ") pressure=%f",
                                 target, point->x, point->y,
                                 msg->inject_touch_event.pressure);
                        break;
                    case AMOTION_EVENT_ACTION_MOVE:
                        LOG_CMSG("%sMove at (%" PRIi32 ", %" PRIi32
                                 ") pressure=%f",
                                 target, point->x, point->y,
                                 msg->inject_touch_event.pressure);
                        break;
                    default:
                        LOG_CMSG("%s%s at (%" PRIi32 ", %" PRIi32
                                 ") pressure=%f",
                                 target, motionevent_action_label_human(action),
                                 point->x, point->y,
                                 msg->inject_touch_event.pressure);
                        break;
                }
            } else {
                LOG_CMSG("pointer id=%" PRIu64_ " %s at (%" PRIi32 ", %" PRIi32
                         ") pressure=%f",
                         pointer_id, motionevent_action_label_human(action),
                         point->x, point->y,
                         msg->inject_touch_event.pressure);
            }
            break;
        }
        case SC_CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT: {
            char buttons[32];
            LOG_CMSG("scroll at (%" PRIi32 ", %" PRIi32 ") h=%f v=%f buttons=%s",
                     msg->inject_scroll_event.position.point.x,
                     msg->inject_scroll_event.position.point.y,
                     msg->inject_scroll_event.hscroll,
                     msg->inject_scroll_event.vscroll,
                     buttons_to_string(msg->inject_scroll_event.buttons,
                                       buttons));
            break;
        }
        case SC_CONTROL_MSG_TYPE_BACK_OR_SCREEN_ON:
            LOG_CMSG("backOrScreenOn %s",
                     KEYEVENT_ACTION_LABEL(msg->back_or_screen_on.action));
            break;
        case SC_CONTROL_MSG_TYPE_GET_CLIPBOARD:
            LOG_CMSG("clipboardGet copyKey=%s",
                     copy_key_labels[msg->get_clipboard.copy_key]);
            break;
        case SC_CONTROL_MSG_TYPE_SET_CLIPBOARD:
            LOG_CMSG("clipboardSet sequence=%" PRIu64_ " %s \"%s\"",
                     msg->set_clipboard.sequence,
                     msg->set_clipboard.paste ? "paste" : "nopaste",
                     msg->set_clipboard.text);
            break;
        case SC_CONTROL_MSG_TYPE_SET_DISPLAY_POWER:
            LOG_CMSG("displayPower %s",
                     msg->set_display_power.on ? "on" : "off");
            break;
        case SC_CONTROL_MSG_TYPE_EXPAND_NOTIFICATION_PANEL:
            LOG_CMSG("expandNotificationPanel");
            break;
        case SC_CONTROL_MSG_TYPE_EXPAND_SETTINGS_PANEL:
            LOG_CMSG("expandSettingsPanel");
            break;
        case SC_CONTROL_MSG_TYPE_COLLAPSE_PANELS:
            LOG_CMSG("collapsePanels");
            break;
        case SC_CONTROL_MSG_TYPE_ROTATE_DEVICE:
            LOG_CMSG("rotateDevice");
            break;
        case SC_CONTROL_MSG_TYPE_UHID_CREATE:
            LOG_CMSG("uhidCreate id=%u name=\"%s\" reportDescSize=%u",
                     (unsigned) msg->uhid_create.id,
                     msg->uhid_create.name ? msg->uhid_create.name : "",
                     (unsigned) msg->uhid_create.report_desc_size);
            break;
        case SC_CONTROL_MSG_TYPE_UHID_INPUT:
            LOG_CMSG("uhidInput id=%u size=%u",
                     (unsigned) msg->uhid_input.id,
                     (unsigned) msg->uhid_input.size);
            break;
        case SC_CONTROL_MSG_TYPE_UHID_DESTROY:
            LOG_CMSG("uhidDestroy id=%u", (unsigned) msg->uhid_destroy.id);
            break;
        case SC_CONTROL_MSG_TYPE_OPEN_HARD_KEYBOARD_SETTINGS:
            LOG_CMSG("openHardKeyboardSettings");
            break;
        default:
            LOG_CMSG("unknownType=%u", (unsigned) msg->type);
            break;
    }
#undef LOG_CMSG
}

bool
sc_control_msg_is_droppable(const struct sc_control_msg *msg) {
    // Cannot drop UHID_CREATE messages, because it would cause all further
    // UHID_INPUT messages for this device to be invalid.
    // Cannot drop UHID_DESTROY messages either, because a further UHID_CREATE
    // with the same id may fail.
    return msg->type != SC_CONTROL_MSG_TYPE_UHID_CREATE
        && msg->type != SC_CONTROL_MSG_TYPE_UHID_DESTROY;
}

void
sc_control_msg_destroy(struct sc_control_msg *msg) {
    switch (msg->type) {
        case SC_CONTROL_MSG_TYPE_INJECT_TEXT:
            free(msg->inject_text.text);
            break;
        case SC_CONTROL_MSG_TYPE_SET_CLIPBOARD:
            free(msg->set_clipboard.text);
            break;
        case SC_CONTROL_MSG_TYPE_START_APP:
            free(msg->start_app.name);
            break;
        default:
            // do nothing
            break;
    }
}
