#include "common.h"

#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "control_msg.h"
#include "util/log.h"

static char last_message[512];
static int last_category;
static SDL_LogPriority last_priority;
static unsigned message_count;

static void SDLCALL
capture_log(void *userdata, int category, SDL_LogPriority priority,
            const char *message) {
    (void) userdata;
    last_category = category;
    last_priority = priority;
    size_t len = strlen(message);
    assert(len < sizeof(last_message));
    memcpy(last_message, message, len + 1);
    ++message_count;
}

static void
assert_logged(const struct sc_control_msg *msg, const char *expected) {
    unsigned count = message_count;
    sc_control_msg_log_human(msg);
    assert(message_count == count + 1);
    assert(last_category == SC_LOG_CATEGORY_USER_ACTION);
    assert(last_priority == SDL_LOG_PRIORITY_INFO);
    assert(!strcmp(last_message, expected));
}

static void test_disabled_by_default(void) {
    sc_set_log_user_action_enabled(false);
    sc_set_log_level(SC_LOG_LEVEL_VERBOSE);

    struct sc_control_msg msg = {
        .type = SC_CONTROL_MSG_TYPE_ROTATE_DEVICE,
    };
    unsigned count = message_count;
    sc_control_msg_log_human(&msg);
    assert(message_count == count);
}

static void test_follows_log_level(void) {
    sc_set_log_user_action_enabled(true);
    sc_set_log_level(SC_LOG_LEVEL_WARN);

    struct sc_control_msg msg = {
        .type = SC_CONTROL_MSG_TYPE_ROTATE_DEVICE,
    };
    unsigned count = message_count;
    sc_control_msg_log_human(&msg);
    assert(message_count == count);
}

static void test_toggle_after_log_level(void) {
    sc_set_log_user_action_enabled(false);
    sc_set_log_level(SC_LOG_LEVEL_INFO);

    struct sc_control_msg msg = {
        .type = SC_CONTROL_MSG_TYPE_ROTATE_DEVICE,
    };
    unsigned count = message_count;
    sc_control_msg_log_human(&msg);
    assert(message_count == count);

    sc_set_log_user_action_enabled(true);
    assert_logged(&msg, "send: rotateDevice");

    sc_set_log_user_action_enabled(false);
    count = message_count;
    sc_control_msg_log_human(&msg);
    assert(message_count == count);
}

static void test_every_message_type(void) {
    sc_set_log_user_action_enabled(true);
    sc_set_log_level(SC_LOG_LEVEL_INFO);

    struct sc_control_msg msg;

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_INJECT_KEYCODE,
        .inject_keycode = {
            .action = AKEY_EVENT_ACTION_DOWN,
            .keycode = AKEYCODE_ENTER,
        },
    };
    assert_logged(&msg, "send: keyDown code=66");

    msg.inject_keycode.action = 42;
    assert_logged(&msg, "send: key action=42 code=66");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_INJECT_TEXT,
        .inject_text.text = "hello",
    };
    assert_logged(&msg, "send: text \"hello\"");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT,
        .inject_touch_event = {
            .action = AMOTION_EVENT_ACTION_DOWN,
            .action_button = AMOTION_EVENT_BUTTON_PRIMARY,
            .buttons = AMOTION_EVENT_BUTTON_PRIMARY,
            .pointer_id = SC_POINTER_ID_MOUSE,
            .position.point = {.x = 10, .y = 20},
            .pressure = 1.0f,
        },
    };
    assert_logged(&msg, "send: leftClickDown at (10, 20)");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT,
        .inject_scroll_event.position.point = {.x = 1, .y = 2},
    };
    assert_logged(&msg,
                  "send: scroll at (1, 2) h=0.000000 v=0.000000 buttons=none");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_BACK_OR_SCREEN_ON,
        .back_or_screen_on.action = AKEY_EVENT_ACTION_UP,
    };
    assert_logged(&msg, "send: backOrScreenOn up");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_EXPAND_NOTIFICATION_PANEL,
    };
    assert_logged(&msg, "send: expandNotificationPanel");

    msg.type = SC_CONTROL_MSG_TYPE_EXPAND_SETTINGS_PANEL;
    assert_logged(&msg, "send: expandSettingsPanel");

    msg.type = SC_CONTROL_MSG_TYPE_COLLAPSE_PANELS;
    assert_logged(&msg, "send: collapsePanels");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_GET_CLIPBOARD,
        .get_clipboard.copy_key = SC_COPY_KEY_COPY,
    };
    assert_logged(&msg, "send: clipboardGet copyKey=copy");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_SET_CLIPBOARD,
        .set_clipboard = {
            .sequence = 7,
            .text = "abc",
            .paste = true,
        },
    };
    assert_logged(&msg, "send: clipboardSet sequence=7 paste \"abc\"");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_SET_DISPLAY_POWER,
        .set_display_power.on = true,
    };
    assert_logged(&msg, "send: displayPower on");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_ROTATE_DEVICE,
    };
    assert_logged(&msg, "send: rotateDevice");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_UHID_CREATE,
        .uhid_create = {
            .id = 1,
            .name = NULL,
            .report_desc_size = 3,
        },
    };
    assert_logged(&msg, "send: uhidCreate id=1 name=\"\" reportDescSize=3");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_UHID_INPUT,
        .uhid_input = {
            .id = 1,
            .size = 4,
        },
    };
    assert_logged(&msg, "send: uhidInput id=1 size=4");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_UHID_DESTROY,
        .uhid_destroy.id = 1,
    };
    assert_logged(&msg, "send: uhidDestroy id=1");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_OPEN_HARD_KEYBOARD_SETTINGS,
    };
    assert_logged(&msg, "send: openHardKeyboardSettings");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_START_APP,
        .start_app.name = "org.example.app",
    };
    assert_logged(&msg, "send: startApp name=\"org.example.app\"");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_RESET_VIDEO,
    };
    assert_logged(&msg, "send: resetVideo");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_CAMERA_SET_TORCH,
        .camera_set_torch.on = false,
    };
    assert_logged(&msg, "send: cameraTorch off");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_CAMERA_ZOOM_IN,
    };
    assert_logged(&msg, "send: cameraZoomIn");

    msg.type = SC_CONTROL_MSG_TYPE_CAMERA_ZOOM_OUT;
    assert_logged(&msg, "send: cameraZoomOut");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_RESIZE_DISPLAY,
        .resize_display = {
            .width = 1920,
            .height = 1080,
        },
    };
    assert_logged(&msg, "send: resizeDisplay 1920x1080");

    msg = (struct sc_control_msg) {
        .type = SC_CONTROL_MSG_TYPE_SCAN_FILE,
        .scan_file.path = "/sdcard/Download/file.mp4",
    };
    assert_logged(&msg, "send: scanFile path=\"/sdcard/Download/file.mp4\"");

    // Unknown types must be logged, not rejected
    msg = (struct sc_control_msg) {
        .type = (enum sc_control_msg_type) 200,
    };
    assert_logged(&msg, "send: unknownType=200");
}

int main(int argc, char *argv[]) {
    (void) argc;
    (void) argv;

    SDL_SetLogOutputFunction(capture_log, NULL);

    test_disabled_by_default();
    test_follows_log_level();
    test_toggle_after_log_level();
    test_every_message_type();
    return 0;
}
