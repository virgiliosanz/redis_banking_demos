#!/bin/sh
set -eu

if [ "$#" -gt 1 ]; then
  printf '%s\n' "Usage: ./scripts/redact-sensitive.sh [file]" >&2
  exit 1
fi

if [ "$#" -eq 1 ]; then
  input_cmd="cat -- \"$1\""
else
  input_cmd="cat"
fi

eval "$input_cmd" | perl -0pe '
  sub is_private_ipv4 {
    my ($ip) = @_;
    my @o = split /\./, $ip;
    return 0 unless @o == 4;
    for my $octet (@o) {
      return 0 unless $octet =~ /^\d+$/ && $octet >= 0 && $octet <= 255;
    }

    return 1 if $o[0] == 10;
    return 1 if $o[0] == 127;
    return 1 if $o[0] == 192 && $o[1] == 168;
    return 1 if $o[0] == 172 && $o[1] >= 16 && $o[1] <= 31;
    return 1 if $o[0] == 169 && $o[1] == 254;
    return 0;
  }

  s/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/[REDACTED_EMAIL]/gi;
  s/\b((?:\d{1,3}\.){3}\d{1,3})\b/is_private_ipv4($1) ? $1 : "[REDACTED_IP]"/ge;
'
