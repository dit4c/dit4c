#!/usr/bin/zsh
#
# Currently DIT4C portal's SSH REPL is provided by Ammonite-SSHD, which uses
# jline2 for terminal interactions. Unfortunately, this means it requires a
# TTY to work correctly.
#
# This script wraps the entire portal server in its own pseudo-terminal as a
# workaround.
#

zmodload zsh/zpty

zpty -b -e dit4c-portal TERM=dumb /opt/dit4c-portal/bin/dit4c-portal "$@"

while zpty -t dit4c-portal
do
  zpty -r dit4c-portal
done
