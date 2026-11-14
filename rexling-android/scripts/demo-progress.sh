#!/bin/sh
# Run inside a Rex terminal while viewing its tab in Rexling.
clear_progress() { printf '\033]9;4;0\007'; }
trap clear_progress EXIT
trap 'clear_progress; exit 130' INT TERM
printf 'OSC 9;4 progress demo\n'
for percent in 0 25 50 75 100; do
    printf '\033]9;4;1;%s\007' "$percent"
    printf 'Progress: %s%%\n' "$percent"
    sleep 1
done
printf '\033]9;4;4;75\007'
printf 'Paused at 75%%\n'
sleep 2
printf '\033]9;4;2;75\007'
printf 'Error state at 75%%\n'
sleep 2
printf '\033]9;4;3\007'
printf 'Indeterminate progress\n'
sleep 2
clear_progress
printf 'Progress cleared (no completion notification is inferred).\n'
