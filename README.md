# Virtual Controller

Turns an Android phone into an Xbox 360 controller for Windows games over Wi-Fi.

Tested working in Forza Horizon 4 and GTA V.

## How it works

Phone (Kotlin) → JSON over UDP → Python receiver → ViGEm virtual gamepad → game

The phone samples its gyroscope and touch input, and sends state at 60 Hz to a
listener on the PC. The receiver drains the socket to the newest packet only and
writes to a virtual XInput device, so Windows sees a real Xbox 360 controller.

## Modes

**Driving** — steer by tilting the phone, or with an on-screen wheel. Throttle
and reverse come from pitch, with live indicators for steering angle and
gas/reverse level. Horn, headlights, handbrake, and a look-around stick.

**Xbox** — full controller layout: two sticks, D-pad, ABXY, bumpers, triggers,
stick clicks, Start/Back. For navigating menus, which tilt steering can't do.

Calibration is persisted, so the neutral position survives mode switches and
restarts.

## Running it

**PC:** install [ViGEmBus](https://github.com/nefarius/ViGEmBus), then
`pip install vgamepad` and run `tools/receiver.py`. Allow Python through the
firewall on your private network.

**Phone:** build and install the app, enter the PC's LAN IP, tap Connect.

## Repo layout

    app/            Android app
    tools/receiver.py   UDP listener, maps packets to the virtual pad
    tools/convert.py    Converts Figma SVG exports to Android VectorDrawables

## Notes

Button mappings are per-game and live in a table at the top of `receiver.py` —
Forza uses A for handbrake, GTA V uses RB.