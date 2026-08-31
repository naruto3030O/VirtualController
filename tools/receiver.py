"""
UDP -> virtual Xbox 360 pad.

Two changes that matter for input feel:

1. No per-packet printing. The old version printed every packet at 60 Hz.
   Windows console writes are synchronous and slow, so the loop fell behind,
   the UDP receive buffer backed up, and the gamepad ended up acting on
   packets that were hundreds of milliseconds old. That is what made the
   sticks look random and made short button taps disappear.

2. The socket is drained every iteration and only the NEWEST packet is
   applied. For a controller, a queued packet is a wrong packet.

Set VERBOSE = True only when you actually need to inspect traffic.
"""

import errno
import json
import socket
import time

import vgamepad as vg

HOST = "0.0.0.0"
PORT = 5005

VERBOSE = False          # per-packet dump; leave off while playing
LOG_BUTTON_CHANGES = True  # cheap: only prints when the button set changes

TIMEOUT_SECONDS = 1.0

# Driving-mode buttons. The phone now sends these by name in a "buttons"
# array, exactly like Xbox mode, so adding a control is one line here.
# Mappings are per-game; change the right-hand side to match what you play.
#   Forza Horizon 4/5:  handbrake=A,  horn=LEFT_THUMB, headlight=DPAD_UP
#   GTA V:              handbrake=RIGHT_SHOULDER, horn=LEFT_THUMB,
#                       headlight=DPAD_UP
DRIVING_BUTTON_MAP = {
    "handbrake": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
    "horn": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
    "headlight": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
}

# Legacy single-button handbrake, kept for the old CSV packet format.
# The correct choice is per-game, not per-controller:
#   Forza Horizon 4/5 ....... XUSB_GAMEPAD_A
#   GTA V ................... XUSB_GAMEPAD_RIGHT_SHOULDER
#   Assetto Corsa ........... XUSB_GAMEPAD_A
#   Most arcade racers ...... XUSB_GAMEPAD_A
# Change this one line to match whatever you are playing.
HANDBRAKE_BUTTON = vg.XUSB_BUTTON.XUSB_GAMEPAD_A


def clamp(value, low, high):
    return max(low, min(high, value))


gamepad = vg.VX360Gamepad()

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))
sock.setblocking(False)

# A generous receive buffer plus draining means we never act on stale input.
try:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 16)
except OSError:
    pass

BUTTON_MAP = {
    "a": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
    "b": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
    "x": vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
    "y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
    "lb": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
    "rb": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
    "start": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
    "back": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
    "dpad_up": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
    "dpad_down": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
    "dpad_left": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
    "dpad_right": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    "ls": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
    "rs": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
}

pressed_buttons = set()
handbrake_on = False
last_packet = time.monotonic()
last_mode = None


def drain_socket():
    """Return the most recent datagram, discarding anything queued behind it."""
    newest = None

    while True:
        try:
            data, _addr = sock.recvfrom(2048)
            newest = data
        except BlockingIOError:
            break
        except OSError as exc:
            if exc.errno in (errno.EWOULDBLOCK, errno.EAGAIN):
                break
            # WSAECONNRESET (10054) happens on Windows when a previous send
            # was refused; it is harmless for a listening UDP socket.
            if getattr(exc, "winerror", None) == 10054:
                continue
            raise

    return newest


def parse(raw):
    """JSON first, legacy 5-field CSV as a fallback."""
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        parts = raw.split(",")

        if len(parts) != 5:
            return None

        return {
            "mode": 0,
            "steering": float(parts[0]),
            "gas": float(parts[1]),
            "reverse": float(parts[2]),
            "handbrake": int(parts[3]),
            "gear": int(parts[4]),
        }


def release_all():
    global handbrake_on, pressed_buttons

    gamepad.reset()
    gamepad.update()

    handbrake_on = False
    pressed_buttons = set()


print(f"Listening on UDP {PORT}. Verbose={VERBOSE}")

try:

    while True:

        now = time.monotonic()


        payload = drain_socket()

        if payload is None:

            if now - last_packet > TIMEOUT_SECONDS:
                release_all()
                last_packet = now

            time.sleep(0.002)
            continue

        try:
            raw = payload.decode().strip()
            packet = parse(raw)
        except UnicodeDecodeError:
            continue

        if packet is None:
            continue

        if VERBOSE:
            print(raw)

        try:
            mode = int(packet.get("mode", 0))
        except (TypeError, ValueError):
            mode = 0

        # Switching modes should never leave the other mode's state latched.
        if mode != last_mode:
            release_all()
            last_mode = mode

        try:

            # =================================================
            # DRIVING MODE
            # =================================================

            if mode == 0:

                steering = clamp(float(packet.get("steering", 0.0)), -1.0, 1.0)
                gas = clamp(float(packet.get("gas", 0.0)), 0.0, 1.0)
                reverse = clamp(float(packet.get("reverse", 0.0)), 0.0, 1.0)

                # Look-around stick
                rx = clamp(float(packet.get("rx", 0.0)), -1.0, 1.0)
                ry = clamp(float(packet.get("ry", 0.0)), -1.0, 1.0)

                gamepad.left_joystick_float(
                    x_value_float=steering,
                    y_value_float=0.0,
                )

                gamepad.right_joystick_float(
                    x_value_float=rx,
                    y_value_float=ry,
                )

                gamepad.right_trigger_float(gas)
                gamepad.left_trigger_float(reverse)

                # Named buttons, same mechanism as Xbox mode.
                current_buttons = set(packet.get("buttons", []))

                # Legacy CSV packets still send a plain handbrake int.
                if int(packet.get("handbrake", 0)):
                    current_buttons.add("handbrake")

                if current_buttons != pressed_buttons:

                    for name in current_buttons - pressed_buttons:
                        if name in DRIVING_BUTTON_MAP:
                            gamepad.press_button(button=DRIVING_BUTTON_MAP[name])

                    for name in pressed_buttons - current_buttons:
                        if name in DRIVING_BUTTON_MAP:
                            gamepad.release_button(button=DRIVING_BUTTON_MAP[name])

                    if LOG_BUTTON_CHANGES:
                        print("driving buttons:", sorted(current_buttons) or "-")

                    pressed_buttons = current_buttons

            # =================================================
            # XBOX MODE
            # =================================================

            else:

                lx = clamp(float(packet.get("lx", 0.0)), -1.0, 1.0)
                ly = clamp(float(packet.get("ly", 0.0)), -1.0, 1.0)
                rx = clamp(float(packet.get("rx", 0.0)), -1.0, 1.0)
                ry = clamp(float(packet.get("ry", 0.0)), -1.0, 1.0)

                lt = clamp(float(packet.get("lt", 0.0)), 0.0, 1.0)
                rt = clamp(float(packet.get("rt", 0.0)), 0.0, 1.0)

                gamepad.left_joystick_float(x_value_float=lx, y_value_float=ly)
                gamepad.right_joystick_float(x_value_float=rx, y_value_float=ry)

                gamepad.left_trigger_float(lt)
                gamepad.right_trigger_float(rt)

                current_buttons = set(packet.get("buttons", []))

                if current_buttons != pressed_buttons:

                    for name in current_buttons - pressed_buttons:
                        if name in BUTTON_MAP:
                            gamepad.press_button(button=BUTTON_MAP[name])

                    for name in pressed_buttons - current_buttons:
                        if name in BUTTON_MAP:
                            gamepad.release_button(button=BUTTON_MAP[name])

                    if LOG_BUTTON_CHANGES:
                        print("buttons:", sorted(current_buttons) or "-")

                    pressed_buttons = current_buttons

            gamepad.update()
            last_packet = now

        except (ValueError, TypeError, KeyError) as exc:
            print(f"Bad packet: {exc}")

except KeyboardInterrupt:
    print("\nStopping controller.")
    release_all()
    sock.close()
