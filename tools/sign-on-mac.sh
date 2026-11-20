#!/bin/bash
# The whole sign on one computer, with no hardware: this app in the stock Android emulator, its
# serial line connected to the sign's firmware running in the firmware emulator
# (arduino-led-marquee, emulator/). See README.md, "The whole sign on a Mac".
#
#   tools/sign-on-mac.sh [up] [options] [-- more options for sign-emu]
#       brings it all up and shows the sign in this terminal, until Ctrl-C
#   tools/sign-on-mac.sh send '!game'      hands the running app a command, as a phone would
#   tools/sign-on-mac.sh key up            presses a key of the sign's keyboard: up, space,
#                                          enter, esc, or any KEYCODE_ name
#   tools/sign-on-mac.sh down              stops the Android emulator
#
# EVERY adb COMMAND HERE NAMES AN ANDROID EMULATOR (-s emulator-<port>), and the script refuses
# any other serial: a real sign on this computer's USB is never spoken to.

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$HERE")"
PACKAGE=cx.aphex.energysign

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"

FIRMWARE="${SIGN_FIRMWARE:-$(dirname "$REPO")/arduino-led-marquee}"
EMU=""
IMAGE=""
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
AVD="${SIGN_AVD:-EnergySign}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
ADB_PORT="${SIGN_ADB_PORT:-5584}"
LINK_PORT="${SIGN_LINK_PORT:-14400}"
HEADLESS=0
KEEP=0
INSTALL=1
RECORD=""
FRAMES=""
DURATION=""
BOOT_WAIT_S=600

usage() {
    cat <<EOF
usage: tools/sign-on-mac.sh [up] [options] [-- more options for sign-emu]
       tools/sign-on-mac.sh send <text>   |   key <up|space|enter|esc|KEYCODE_...>   |   down

  --firmware <dir>   a checkout of arduino-led-marquee with its emulator built (make -C emulator)
                     (default: \$SIGN_FIRMWARE, or arduino-led-marquee beside this checkout)
  --emu <file>       the firmware emulator        (default: <firmware>/emulator/build/sign-emu)
  --image <file>     the firmware image, a .hex   (default: <firmware>/emulator/build/firmware/LED_Marquee.hex)
  --apk <file>       the app build to install     (default: this checkout's debug build)
  --no-install       leave the app that is installed
  --avd <name>       the Android virtual device   (default: \$SIGN_AVD, or EnergySign: a small
                     one of the sign's own, made from an installed system image when it is
                     not there. A device in daily use is often too full to take the app.)
  --adb-port <n>     the Android emulator's console port, even, 5554 to 5682 (default 5584:
                     the device is then emulator-5584). One that runs there already is used.
  --link-port <n>    the TCP port of the sign's serial line (default 14400)
  --headless         no window and no terminal view: for a script. Use with:
  --record <gif>     what the sign showed, as a GIF     (written by the firmware emulator)
  --frames <jsonl>   every frame and every byte on the line, for a test
  --duration <s>     stop after this many seconds of the sign's time
  --keep             leave the Android emulator running at the end
EOF
}

die() {
    echo "sign-on-mac: $*" >&2
    exit 1
}

# The one way to adb. A serial that is not an Android emulator's is refused, whatever was asked.
adb_emu() {
    case "$SERIAL" in
        emulator-[0-9]*) "$ADB" -s "$SERIAL" "$@" ;;
        *) die "refused: '$SERIAL' is not an Android emulator (emulator-<port>); nothing was sent" ;;
    esac
}

running() { [ "$(adb_emu get-state 2>/dev/null)" = "device" ]; }

# A small virtual device of the sign's own, from a system image that is installed (nothing is
# downloaded): the newest with Google's APIs for this computer's processor, or else the newest.
make_avd() {
    local abi arch image api tag dir
    case "$(uname -m)" in
        arm64) abi=arm64-v8a; arch=arm64 ;;
        *) abi=x86_64; arch=x86_64 ;;
    esac
    image="$(cd "$SDK" 2>/dev/null && ls -d system-images/android-[0-9]*/google_apis/"$abi" 2>/dev/null | sort -t- -k3 -n | tail -1)"
    [ -n "$image" ] || image="$(cd "$SDK" 2>/dev/null && ls -d system-images/android-[0-9]*/*/"$abi" 2>/dev/null | sort -t- -k3 -n | tail -1)"
    [ -n "$image" ] || die "no Android system image for $abi is installed in $SDK (install one in Android Studio, or --avd)"
    api="$(echo "$image" | cut -d/ -f2)"
    tag="$(echo "$image" | cut -d/ -f3)"
    dir="$AVD_HOME/$AVD.avd"
    echo "sign-on-mac: making the virtual device $AVD from $image"
    mkdir -p "$dir" || die "cannot make $dir"
    printf '%s\n' "avd.ini.encoding=UTF-8" "path=$dir" "path.rel=avd/$AVD.avd" "target=$api" | tee "$AVD_HOME/$AVD.ini" >/dev/null
    printf '%s\n' \
        "AvdId=$AVD" "avd.ini.displayname=$AVD" "avd.ini.encoding=UTF-8" "PlayStore.enabled=false" \
        "abi.type=$abi" "hw.cpu.arch=$arch" "hw.cpu.ncore=2" "hw.ramSize=2048" "vm.heapSize=256" \
        "disk.dataPartition.size=2G" "hw.lcd.width=480" "hw.lcd.height=800" "hw.lcd.density=160" \
        "hw.keyboard=yes" "hw.gpu.enabled=yes" "hw.gpu.mode=auto" "hw.sdCard=no" "hw.audioInput=no" \
        "hw.camera.back=none" "hw.camera.front=none" "image.sysdir.1=$image/" "tag.id=$tag" "tag.display=$tag" \
        "target=$api" "showDeviceFrame=no" | tee "$dir/config.ini" >/dev/null
}

need_running() { running || die "no Android emulator is running as $SERIAL (start it: tools/sign-on-mac.sh up)"; }

command=up
case "${1:-}" in
    up | send | key | down) command="$1"; shift ;;
    -h | --help | help) usage; exit 0 ;;
esac

words=()
while [ $# -gt 0 ]; do
    case "$1" in
        --firmware) FIRMWARE="$2"; shift 2 ;;
        --emu) EMU="$2"; shift 2 ;;
        --image) IMAGE="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --no-install) INSTALL=0; shift ;;
        --avd) AVD="$2"; shift 2 ;;
        --adb-port) ADB_PORT="$2"; shift 2 ;;
        --link-port) LINK_PORT="$2"; shift 2 ;;
        --headless) HEADLESS=1; shift ;;
        --record) RECORD="$2"; shift 2 ;;
        --frames) FRAMES="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        -h | --help) usage; exit 0 ;;
        --) shift; break ;;
        -*) usage >&2; die "unknown option $1" ;;
        *) words+=("$1"); shift ;;
    esac
done

case "$ADB_PORT" in
    *[!0-9]* | "") die "--adb-port must be a number" ;;
esac
[ "$ADB_PORT" -ge 5554 ] && [ "$ADB_PORT" -le 5682 ] && [ $((ADB_PORT % 2)) -eq 0 ] || die "--adb-port must be even, 5554 to 5682"
SERIAL="emulator-$ADB_PORT"
[ -x "$ADB" ] || die "no adb at $ADB (set ANDROID_HOME)"

case "$command" in
    send)
        [ ${#words[@]} -eq 1 ] || die "send takes one text, in quotes: send '!game'"
        need_running
        # base64, so that no shell on the way changes a character of it
        adb_emu shell am broadcast -n "$PACKAGE/.adb.AdbCommandReceiver" -a "$PACKAGE.COMMAND" \
            --es b64 "$(printf '%s' "${words[0]}" | base64 | tr -d '\n')"
        exit $?
        ;;
    key)
        [ ${#words[@]} -eq 1 ] || die "key takes one key: up, space, enter, esc, or a KEYCODE_ name"
        case "${words[0]}" in
            up) code=KEYCODE_DPAD_UP ;;
            space) code=KEYCODE_SPACE ;;
            enter) code=KEYCODE_ENTER ;;
            esc) code=KEYCODE_ESCAPE ;;
            KEYCODE_[A-Z0-9_]*) code="${words[0]}" ;;
            *) die "no such key: ${words[0]}" ;;
        esac
        need_running
        adb_emu shell input keyevent "$code"
        exit $?
        ;;
    down)
        running || { echo "sign-on-mac: no Android emulator is running as $SERIAL"; exit 0; }
        adb_emu emu kill
        exit $?
        ;;
esac

# ---- up
[ ${#words[@]} -eq 0 ] || die "up takes options only (got: ${words[*]})"
[ -n "$EMU" ] || EMU="$FIRMWARE/emulator/build/sign-emu"
[ -n "$IMAGE" ] || IMAGE="$FIRMWARE/emulator/build/firmware/LED_Marquee.hex"
[ -x "$EMU" ] || die "no firmware emulator at $EMU (build it: make -C <arduino-led-marquee>/emulator; or --firmware, --emu)"
[ -r "$IMAGE" ] || die "no firmware image at $IMAGE (--image)"
[ "$INSTALL" -eq 0 ] || [ -r "$APK" ] || die "no app build at $APK (./gradlew :app:assembleDebug; or --apk, --no-install)"

started_here=0
emulator_log=""
stop_android() {
    if [ "$started_here" -eq 1 ] && [ "$KEEP" -eq 0 ]; then
        echo "sign-on-mac: stopping the Android emulator $SERIAL"
        # by its console, which saves its state; while it is starting there is no console yet,
        # and then it is ended by its process
        adb_emu emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null
    fi
}
trap stop_android EXIT
trap 'exit 130' INT TERM

if running; then
    echo "sign-on-mac: using the Android emulator that runs as $SERIAL"
else
    [ -x "$EMULATOR" ] || die "no Android emulator at $EMULATOR (set ANDROID_HOME)"
    [ -f "$AVD_HOME/$AVD.ini" ] || make_avd
    emulator_log="$(mktemp -t sign-on-mac-android)"
    window=()
    [ "$HEADLESS" -eq 0 ] || window=(-no-window)
    echo "sign-on-mac: starting the Android emulator $AVD as $SERIAL (its log: $emulator_log)"
    # modest: 2 GB, two cores, no sound. (Its state is saved when it stops, so the next start takes
    # seconds and finds an Android that has settled: a first start is minutes of a busy one.)
    "$EMULATOR" -avd "$AVD" -port "$ADB_PORT" -memory 2048 -cores 2 -no-audio -no-boot-anim \
        ${window[@]+"${window[@]}"} >"$emulator_log" 2>&1 &
    emulator_pid=$!
    started_here=1
    waited=0
    until [ "$(adb_emu shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        kill -0 "$emulator_pid" 2>/dev/null || die "the Android emulator ended while starting; see $emulator_log"
        [ "$waited" -lt "$BOOT_WAIT_S" ] || die "the Android emulator has not started within $BOOT_WAIT_S s; see $emulator_log"
        sleep 2
        waited=$((waited + 2))
    done
fi

if [ "$INSTALL" -eq 1 ]; then
    echo "sign-on-mac: installing $APK"
    if ! said="$(adb_emu install -r -g "$APK" 2>&1)"; then
        case "$said" in
            *"not enough space"* | *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
                # Replacing needs room for both builds, and a virtual device that is in use is often
                # full; and a build signed with another key (another computer's debug key) cannot
                # replace the installed one at all
                echo "sign-on-mac: the installed app cannot be replaced (no room, or another signing key): removing it first (what it had saved there goes with it)"
                adb_emu uninstall "$PACKAGE" >/dev/null 2>&1
                said="$(adb_emu install -g "$APK" 2>&1)" || {
                    echo "$said" | head -3 >&2
                    die "the app could not be installed; room in the Android emulator: $(adb_emu shell df -h /data 2>/dev/null | tail -1)"
                }
                ;;
            *)
                echo "$said" | head -3 >&2
                die "the app could not be installed"
                ;;
        esac
    fi
fi
# where the app finds the sign: this computer's localhost, seen from inside the Android emulator
adb_emu shell setprop debug.energysign.link "10.0.2.2:$LINK_PORT" || die "could not tell the app where the sign is"
adb_emu shell am force-stop "$PACKAGE"
adb_emu shell am start -n "$PACKAGE/.GattServerActivity" >/dev/null || die "the app could not be started"

cat <<EOF

THE SIGN IS UP: the app runs in the Android emulator $SERIAL, the firmware in this terminal.
  A command, as from the phone (in another terminal):   tools/sign-on-mac.sh send '!game'
  The sign's keyboard is the Android emulator's window: click it, then type; the up arrow
  starts the game and jumps. Without the window:        tools/sign-on-mac.sh key up
  The app's log:   $ADB -s $SERIAL logcat -s EnergySignUartManager MessageManager SignUart
  Ctrl-C here ends it$([ "$started_here" -eq 1 ] && [ "$KEEP" -eq 0 ] && echo " and stops the Android emulator").

EOF

sign=(--speed 1 --port "$LINK_PORT")
[ "$HEADLESS" -eq 0 ] || sign+=(--headless)
[ -z "$RECORD" ] || sign+=(--record "$RECORD")
[ -z "$FRAMES" ] || sign+=(--frames "$FRAMES")
[ -z "$DURATION" ] || sign+=(--duration "$DURATION")
[ "$HEADLESS" -eq 1 ] || sleep 3 # (the view takes the whole terminal: time to read the lines above)
"$EMU" "${sign[@]}" "$@" "$IMAGE"
status=$?
[ -z "$RECORD" ] || echo "sign-on-mac: what the sign showed is in $RECORD"
[ -z "$FRAMES" ] || echo "sign-on-mac: every frame and byte is in $FRAMES"
exit $status
