#!/system/bin/sh
# quick-actions: saved contacts and intent templates, turned into one ready
# open_intent call. POSIX sh + awk + od only: that is all an Android phone has.
#
#   sh act.sh run <intent> [param=value ...]   print the open_intent arguments
#   sh act.sh list                            saved intents and contacts
#   sh act.sh contact <name>                  who a name resolves to
#   sh act.sh save-contact <key> <name> <phone> [alias,alias]
#   sh act.sh save-intent <name> <params> <action> <uri> <package> <text> <description>
#   sh act.sh forget-contact <key>
#   sh act.sh forget-intent <name>
#
# Files (tab-separated, "-" means empty):
#   contacts.tsv  key  name  phone  aliases
#   intents.tsv   name  params  action  uri  package  text  description
# Built-in intents ship next to this script; saved ones override them by name.

set -u

DATA="{{QUICK_ACTIONS_DIR}}"
case "$DATA" in *'{{'*) DATA="${HOME:-.}/quick-actions" ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
BUILTIN="$HERE/intents.tsv"
CONTACTS="$DATA/contacts.tsv"
INTENTS="$DATA/intents.tsv"
TAB=$(printf '\t')

mkdir -p "$DATA" && touch "$CONTACTS" "$INTENTS" || { echo "error: cannot write $DATA" >&2; exit 1; }

die() { code=$1; shift; printf '%s\n' "$*"; exit "$code"; }

# Reject values that would break the tab-separated files.
clean() {
  case "$1" in
    *"$TAB"*) die 2 "error: values cannot contain tabs." ;;
  esac
  if [ "$(printf '%s' "$1" | wc -l | tr -d ' ')" != "0" ]; then die 2 "error: values cannot contain line breaks."; fi
}

lower() { printf '%s' "$1" | tr 'A-Z' 'a-z'; }

# Percent-encode every byte except RFC 3986 unreserved characters.
urlencode() {
  printf '%s' "$1" | od -An -tx1 -v | tr -s ' \n' '\n\n' | awk '
    BEGIN {
      for (i = 48; i <= 57; i++) keep[sprintf("%02x", i)] = sprintf("%c", i)
      for (i = 65; i <= 90; i++) keep[sprintf("%02x", i)] = sprintf("%c", i)
      for (i = 97; i <= 122; i++) keep[sprintf("%02x", i)] = sprintf("%c", i)
      keep["2d"] = "-"; keep["2e"] = "."; keep["5f"] = "_"; keep["7e"] = "~"
    }
    NF { printf "%s", ($1 in keep) ? keep[$1] : "%" toupper($1) }'
}

# One contact row whose key, name or any alias equals $1 (case-insensitive).
find_contact() {
  QA_WANT=$(lower "$1") awk -F '\t' '
    function norm(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return tolower(s) }
    BEGIN { want = norm(ENVIRON["QA_WANT"]) }
    {
      hit = (norm($1) == want || norm($2) == want)
      n = split($4, alias, ",")
      for (i = 1; i <= n; i++) if (norm(alias[i]) == want) hit = 1
      if (hit) { print; found = 1; exit }
    }
    END { exit found ? 0 : 1 }' "$CONTACTS"
}

# The intent row named $1: a saved one wins over a built-in.
find_intent() {
  QA_WANT="$1" awk -F '\t' '
    $1 == ENVIRON["QA_WANT"] { row = $0 }
    END { if (row == "") exit 1; print row }' "$BUILTIN" "$INTENTS"
}

field() { printf '%s\n' "$1" | awk -F '\t' -v n="$2" '{ v = $n; print (v == "-" ? "" : v) }'; }

# Rewrite $1 without the row whose first column matches $2 (case-insensitive).
drop_row() {
  tmp="$1.tmp"
  QA_KEY=$(lower "$2") awk -F '\t' 'tolower($1) != ENVIRON["QA_KEY"]' "$1" > "$tmp" && mv "$tmp" "$1"
}

cmd_run() {
  [ $# -ge 1 ] || die 2 "usage: act.sh run <intent> [param=value ...]"
  name=$1; shift
  row=$(find_intent "$name") || die 3 "error: no intent named \"$name\". Run: sh $0 list"
  params=$(field "$row" 2); action=$(field "$row" 3); uri=$(field "$row" 4)
  package=$(field "$row" 5); text=$(field "$row" 6)

  set -f
  for arg in "$@"; do
    case "$arg" in
      *=*) key=${arg%%=*}; value=${arg#*=} ;;
      *) die 2 "error: \"$arg\" is not param=value." ;;
    esac
    case "$key" in
      *[!a-z_]*|'') die 2 "error: parameter names are lowercase letters and _: \"$key\"." ;;
    esac
    export "QA_P_$key=$value"
    export "QA_E_$key=$(urlencode "$value")"
  done
  set +f

  # A contact parameter fills {name} and {phone} from the saved contacts.
  if [ -n "${QA_P_contact:-}" ]; then
    contact_row=$(find_contact "$QA_P_contact") || die 4 "error: no saved contact \"$QA_P_contact\". Find the number (ask the user or look it up), confirm it, then: sh $0 save-contact <key> <name> <phone> [aliases]. Then run this again."
    export QA_P_name="$(field "$contact_row" 2)" QA_P_phone="$(field "$contact_row" 3)"
    export QA_E_name="$(urlencode "$QA_P_name")" QA_E_phone="$QA_P_phone"
  fi

  for p in $(printf '%s' "$params" | tr ',' ' '); do
    eval "have=\${QA_P_$p:-}"
    [ -n "$have" ] || die 2 "error: intent \"$name\" needs $p=... (needs: $params)."
  done

  QA_URI="$uri" QA_TEXT="$text" QA_PACKAGE="$package" QA_ACTION="$action" awk '
    function fill(template, encoded,    out, key, value, start, end) {
      out = ""
      while ((start = index(template, "{")) > 0) {
        end = index(substr(template, start), "}")
        if (end == 0) break
        key = substr(template, start + 1, end - 2)
        value = ENVIRON[(encoded ? "QA_E_" : "QA_P_") key]
        if (!((encoded ? "QA_E_" : "QA_P_") key in ENVIRON)) missing = missing " {" key "}"
        out = out substr(template, 1, start - 1) value
        template = substr(template, start + end)
      }
      return out template
    }
    function json(s) {
      gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s)
      gsub(/\n/, "\\n", s); gsub(/\r/, "\\r", s); gsub(/\t/, "\\t", s)
      return "\"" s "\""
    }
    BEGIN {
      uri = fill(ENVIRON["QA_URI"], 1)
      text = fill(ENVIRON["QA_TEXT"], 0)
      if (missing != "") { print "error: the template uses" missing " but no value was given."; exit 2 }
      args = ""
      if (ENVIRON["QA_ACTION"] != "") args = args "\"action\":" json(ENVIRON["QA_ACTION"]) ","
      args = args "\"uri\":" json(uri)
      if (ENVIRON["QA_PACKAGE"] != "") args = args ",\"package\":" json(ENVIRON["QA_PACKAGE"])
      if (text != "") args = args ",\"text\":" json(text)
      print "{\"tool\":\"open_intent\",\"arguments\":{" args "}}"
    }'
}

cmd_list() {
  echo "INTENTS (name | params | what it does)"
  awk -F '\t' '
    NF >= 7 { row[$1] = $2 " | " $7; if (!($1 in seen)) { order[++n] = $1; seen[$1] = 1 } }
    END { for (i = 1; i <= n; i++) print "  " order[i] " | " row[order[i]] }' "$BUILTIN" "$INTENTS"
  echo "CONTACTS (key | name | phone | aliases)"
  awk -F '\t' 'NF >= 3 { print "  " $1 " | " $2 " | +" $3 " | " $4 }' "$CONTACTS"
}

cmd_contact() {
  [ $# -eq 1 ] || die 2 "usage: act.sh contact <name>"
  row=$(find_contact "$1") || die 4 "error: no saved contact \"$1\"."
  printf '%s\n' "$row" | awk -F '\t' '{ print "key=" $1 " name=" $2 " phone=+" $3 " aliases=" $4 }'
}

cmd_save_contact() {
  [ $# -ge 3 ] || die 2 "usage: act.sh save-contact <key> <name> <phone> [alias,alias]"
  key=$1; name=$2; aliases=${4:-}
  for v in "$key" "$name" "$3" "$aliases"; do clean "$v"; done
  phone=$(printf '%s' "$3" | tr -d ' ()-.')
  case "$phone" in
    +*) phone=${phone#+} ;;
    00*) phone=${phone#00} ;;
    0*) die 2 "error: save the number in international form, e.g. 972501234567 for 050-123-4567 in Israel." ;;
  esac
  case "$phone" in
    ''|*[!0-9]*) die 2 "error: \"$3\" is not a phone number." ;;
  esac
  [ ${#phone} -ge 8 ] || die 2 "error: \"$3\" is too short for an international number."
  drop_row "$CONTACTS" "$key"
  printf '%s\t%s\t%s\t%s\n' "$key" "$name" "$phone" "${aliases:--}" >> "$CONTACTS"
  echo "saved contact $key: $name +$phone"
}

cmd_save_intent() {
  [ $# -eq 7 ] || die 2 "usage: act.sh save-intent <name> <params> <action> <uri> <package> <text> <description>  (use - for empty)"
  for v in "$@"; do clean "$v"; done
  case "$1" in *[!a-z0-9._-]*|'') die 2 "error: intent names are lowercase, like whatsapp.send." ;; esac
  [ "$4" != "-" ] || die 2 "error: an intent needs a uri."
  drop_row "$INTENTS" "$1"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >> "$INTENTS"
  echo "saved intent $1"
}

command=${1:-list}
[ $# -gt 0 ] && shift
case "$command" in
  run) cmd_run "$@" ;;
  list) cmd_list ;;
  contact) cmd_contact "$@" ;;
  save-contact) cmd_save_contact "$@" ;;
  save-intent) cmd_save_intent "$@" ;;
  forget-contact) [ $# -eq 1 ] || die 2 "usage: act.sh forget-contact <key>"; drop_row "$CONTACTS" "$1"; echo "forgot contact $1" ;;
  forget-intent) [ $# -eq 1 ] || die 2 "usage: act.sh forget-intent <name>"; drop_row "$INTENTS" "$1"; echo "forgot intent $1" ;;
  *) die 2 "unknown command \"$command\". Commands: run, list, contact, save-contact, save-intent, forget-contact, forget-intent" ;;
esac
