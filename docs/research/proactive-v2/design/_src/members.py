# Content for member.tpl.html: three crew members, same page, different details.
# Invented names only. Hebrew UI text.

CHEV = '<svg class="ic s14 mir"><use href="#i-chev"/></svg>'
CHECK = '<svg class="ic s14"><use href="#i-check"/></svg>'


def chips(*items):
    return "".join(f'<span class="chip">{t}</span>' for t in items)


def item(time, title, cls="", sub="", out=""):
    sub_html = f"<small>{sub}</small>" if sub else ""
    out_html = f'<div class="out">{out}</div>' if out else ""
    return (f'<div class="it {cls}"><span class="mk"></span>'
            f'<div class="row1"><span class="tm">{time}</span><span class="tt">{title}{sub_html}</span></div>{out_html}</div>')


def chip(text, kind="", icon=""):
    return f'<span class="outchip {kind}">{icon}{text}</span>'


def link(text):
    return f'<span class="outlink">{text}{CHEV}</span>'


def now_card(title, sub, bar=True, note=""):
    bar_html = '<div class="bar"><i></i></div>' if bar else ""
    return (f'<section class="now"><div class="card"><span class="label">עכשיו</span>'
            f'<h3 class="title">{title}</h3><p class="sub">{sub}</p>{bar_html}'
            f'{("<p class=sub style=margin-top:10px>" + note + "</p>") if note else ""}</div></section>')


# ------------------------------------------------------------------ Scout
scout_tl = "".join([
    '<div class="day">היום</div>',
    item("עכשיו", "סורק 12 הודעות חדשות ושני אירועים ביומן", "run"),
    item("14:12", "הטופס של דנה", "ok", "וואטסאפ · ביקשה עד יום שלישי",
         chip("הוצעה משימה", "") + chip("אושרה על ידך", "ok", CHECK) + link("לכרטיס")),
    item("13:40", "אישור שעת איסוף", "", "קבוצת הורים כיתה ב׳",
         chip("נדחתה: כבר עשיתי", "") + link("לכרטיס")),
    '<div class="talk"><div class="me">תתעלם מהודעות של ״משלוחים מהירים״</div>'
    '<div class="reply"><canvas class="orb" data-orb="a" data-face="scout" data-state="idle" data-size="28" data-seed="3"></canvas>'
    '<div>בסדר. לא אציע משימות מהתראות של האפליקציה הזו.<small>נשמר בכללים שלך · 13:50</small></div></div></div>',
    '<div class="quietrow">13:05 · סרק ולא מצא כלום חדש</div>',
    '<div class="day">אתמול</div>',
    item("22:10", "ביטול הפגישה עם אורי", "", "אורי כתב שהפגישה בוטלה",
         chip("סומנה כנפתרה", "") + link("לשחזר")),
    '<div class="quietrow">עוד 14 סריקות בלי משימות חדשות</div>',
])
scout = dict(
    TITLE="Scout", NAME="צופה", FACE="scout", STATE="thinking", HUE="158,197,232", SCREEN_CLASS="",
    ROLE="סורק הודעות ויומן ומחפש משימות",
    STATE_CLASS="teal", STATE_HTML='<i class="dot teal live"></i>סורק עכשיו',
    NOW_HTML=now_card("סורק 12 הודעות חדשות", "וואטסאפ ו-Gmail · התחיל לפני 3 דקות",
                      note="הסריקה הבאה בעוד כ-20 דקות, או כשמגיעות הודעות חדשות"),
    COUNT="6", TIMELINE_HTML=scout_tl,
    PLAYBOOK_TITLE="ספר ההנחיות של צופה", PLAYBOOK_SUB="9 כללים · עודכן הלילה על ידי המאמן",
    SEES_TITLE="מה הוא רואה", SEES_SUB="3 מקורות · 2 אפליקציות מוחרגות",
    CHIPS_HTML=chips("מה אתה רואה עכשיו?", "תפסיק להציע דברים כאלה"),
    PLACEHOLDER="כתבו לצופה",
)

# --------------------------------------------------------------- Librarian
lib_tl = "".join([
    '<div class="day">הלילה · 03:00</div>',
    item("03:06", "מיזג 2 הערות כפולות על הטופס של דנה", "ok", "",
         chip("מוזג", "ok", CHECK) + link("להערות")),
    item("03:04", "הסיר 5 הערות שפג תוקפן", "", "הערות בנות יותר מ-30 יום ללא משימה פתוחה",
         chip("פג תוקף", "")),
    item("03:02", "סימן סתירה: ״הפגישה עם נועם עברה לרביעי״", "warn", "שתי הערות אומרות דברים שונים",
         chip("מחכה לבדיקה שלך", "warn") + link("לבדוק")),
    item("03:00", "התחיל לעבור על 37 הערות", "", ""),
    '<div class="day">אתמול · 03:00</div>',
    item("03:05", "סידר 41 הערות לפי אנשים ופרויקטים", "", "", chip("הושלם", "")),
])
librarian = dict(
    TITLE="Librarian", NAME="ספרן", FACE="librarian", STATE="idle", HUE="188,170,232", SCREEN_CLASS="",
    ROLE="מסדר את הזיכרון כל לילה",
    STATE_CLASS="", STATE_HTML='<i class="dot off"></i>במנוחה · ההרצה הבאה ב-03:00',
    NOW_HTML=now_card("הריצה האחרונה: הלילה 03:00 עד 03:07", "37 הערות נבדקו · 2 מוזגו · 5 הוסרו · סתירה אחת", bar=False),
    COUNT="5", TIMELINE_HTML=lib_tl,
    PLAYBOOK_TITLE="כללי הזיכרון", PLAYBOOK_SUB="6 כללים · כמה זמן לשמור ומתי למזג",
    SEES_TITLE="הערות הזיכרון", SEES_SUB="37 הערות · לפי אדם ופרויקט",
    CHIPS_HTML=chips("מה אתה זוכר על דנה?", "תשכח הכול על דנה"),
    PLACEHOLDER="כתבו לספרן",
)

# ------------------------------------------------------------------- Coach
coach_tl = "".join([
    '<div class="day">הלילה · 03:12</div>',
    item("03:15", "הציע להחריג אפליקציה", "warn", "14 מתוך 15 ההצעות ממנה נדחו",
         chip("מחכה להחלטה שלך", "warn") + link("לכרטיס")),
    item("03:14", "כלל חדש: בבקשות של דנה, להציע טיוטת תשובה", "ok", "אושרו 5 מתוך 5",
         chip("כלל עודכן", "ok", CHECK) + link("לבטל")),
    item("03:14", "כלל חדש: לא להציע משימות מהתראות משלוחים", "ok", "12 מתוך 12 נדחו",
         chip("כלל עודכן", "ok", CHECK) + link("לבטל")),
    item("03:12", "סקר 23 החלטות מ-7 הימים האחרונים", "", "הצעות שאושרו, נדחו ותוקנו"),
    '<div class="day">אתמול</div>',
    item("03:11", "לא שינה כללים", "", "אין עדיין מספיק החלטות חדשות", chip("הושלם", "")),
])
coach = dict(
    TITLE="Coach", NAME="מאמן", FACE="coach", STATE="done", HUE="184,214,140", SCREEN_CLASS="",
    ROLE="בודק החלטות ומשפר את ספר ההנחיות",
    STATE_CLASS="teal", STATE_HTML=CHECK + "סיים הלילה · עדכן 3 כללים",
    NOW_HTML=now_card("עדכן 2 כללים והציע שינוי אחד", "כל השינויים של הלילה בכרטיס אחד בלוח, עם ביטול בלחיצה", bar=False),
    COUNT="5", TIMELINE_HTML=coach_tl,
    PLAYBOOK_TITLE="ספר ההנחיות", PLAYBOOK_SUB="9 כללים · עודכן הלילה",
    SEES_TITLE="מה הוא לא יכול לשנות", SEES_SUB="מקורות, מגבלות, אוטונומיה והכללים שלך",
    CHIPS_HTML=chips("למה שינית את זה?", "תחזיר את הכלל הקודם"),
    PLACEHOLDER="כתבו למאמן",
)

TEMPLATES = {
    "member.tpl.html": [
        ("member.html", scout),
        ("member-librarian.html", librarian),
        ("member-coach.html", coach),
    ]
}
