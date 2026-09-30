#!/usr/bin/env python3
"""
TeachFlow emulator end-to-end tests.

Runs the REAL TeachFlow debug APK (real AccessibilityService, real AccessibilityNodeInfo actions)
on an Android emulator against PracticeFood (android/testapp), a small test app. The script plays
the person: it taps the demonstration, gives commands, answers questions and signs in when asked.

These are NOT the T1–T14 device tests on Zomato/Amazon; they mirror them on a test app.
Every PASS/FAIL below is computed from TeachFlow's own run reports and PracticeFood's log.
"""
import base64, json, os, re, subprocess, sys, time, traceback
import xml.etree.ElementTree as ET

OUT = "e2e-out"
SHOTS = os.path.join(OUT, "screens")
os.makedirs(SHOTS, exist_ok=True)
TF_PKG = "com.teachflow.agent"
RCV = TF_PKG + "/.debug.DebugCommandReceiver"
PF_PKG = "com.teachflow.practicefood"
PF_ACT = PF_PKG + "/.MainActivity"
LOG = []


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    LOG.append(line)


def adb(*args, timeout=90):
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout


def sh(*args, timeout=90):
    return adb("shell", *args, timeout=timeout)


def b64(s):
    return base64.b64encode(s.encode()).decode()


def tf(cmd, **extras):
    """Send a command to TeachFlow's debug receiver and return its JSON status."""
    args = ["am", "broadcast", "-n", RCV, "--es", "cmd", cmd]
    for k, v in extras.items():
        if isinstance(v, bool):
            args += ["--ez", k, "true" if v else "false"]
        elif isinstance(v, int):
            args += ["--ei", k, str(v)]
        elif k in ("command", "text"):
            args += ["--es", k, b64(v)]
        else:
            args += ["--es", k, v]
    out = sh(*args)
    m = re.search(r'data="([^"]*)"', out)
    if not m:
        return {"error": "no data: " + out.strip()[:200]}
    return json.loads(base64.b64decode(m.group(1)))


def shot(name):
    path = os.path.join(SHOTS, name + ".png")
    with open(path, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=60)
    return "screens/" + name + ".png"


def dump():
    for _ in range(4):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        x = adb("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in x:
            return ET.fromstring(x[x.index("<hierarchy"):])
        time.sleep(1)
    return None


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(pattern, root=None):
    root = root or dump()
    if root is None:
        return None
    for n in root.iter("node"):
        for a in ("text", "content-desc"):
            if re.fullmatch(pattern, n.get(a, "") or "", re.I | re.S):
                return n
    return None


def tap_node(n):
    x, y = center(n)
    sh("input", "tap", str(x), str(y))


def tap(pattern, wait=1.8):
    n = find(pattern)
    if n is None:
        log(f"  ! could not find {pattern!r} to tap")
        return False
    tap_node(n)
    time.sleep(wait)
    return True


def tap_add_near(item, wait=2.0):
    root = dump()
    anchor = find(re.escape(item), root)
    adds = [n for n in root.iter("node") if (n.get("text") or "").strip().upper() == "ADD"]
    if anchor is None or not adds:
        log(f"  ! could not find ADD near {item!r}")
        return False
    ay = center(anchor)[1]
    tap_node(min(adds, key=lambda n: abs(center(n)[1] - ay)))
    time.sleep(wait)
    return True


def runs_json():
    raw = adb("exec-out", "run-as", TF_PKG, "cat", "files/runs.json")
    try:
        return json.loads(raw)
    except Exception:
        return []


def pf_log():
    return adb("logcat", "-d", "-s", "PracticeFood:I")


def launch_pf(**extras):
    args = ["am", "start", "-S", "-W", "-n", PF_ACT]
    for k, v in extras.items():
        args += (["--ez", k, "true" if v else "false"] if isinstance(v, bool) else ["--es", k, v])
    sh(*args)


def wait_for(pred, timeout=60, every=1.0):
    t0 = time.time()
    st = {}
    while time.time() - t0 < timeout:
        st = tf("status")
        if pred(st):
            return st
        time.sleep(every)
    return st


# ---------------------------------------------------------------------------------------------
RESULTS = []
TEACH = {}


def setup():
    log("Setting up: enable TeachFlow's accessibility service")
    sh("settings", "put", "secure", "enabled_accessibility_services",
       f"{TF_PKG}/{TF_PKG}.accessibility.TeachFlowAccessibilityService")
    sh("settings", "put", "secure", "accessibility_enabled", "1")
    st = wait_for(lambda s: s.get("service") is True, timeout=60)
    log(f"  service connected: {st.get('service')}")
    tf("reset")
    tf("overlay", on=True)
    return st.get("service") is True


def teach():
    log("E1 TEACH: 'Order a Margherita pizza from Domino's on PracticeFood'")
    adb("logcat", "-c")
    tf("teach", command="Order a Margherita pizza from Domino's on PracticeFood", pkg=PF_PKG, label="PracticeFood")
    time.sleep(1)
    launch_pf()
    time.sleep(3)
    shots = [shot("e1-01-teaching-started")]
    tap(r"Search for restaurant, item or more")
    field = None
    root = dump()
    for n in root.iter("node"):
        if n.get("class") == "android.widget.EditText":
            field = n
            break
    if field is not None:
        tap_node(field)
        time.sleep(1)
    sh("input", "text", "Margherita")
    time.sleep(2.5)
    shots.append(shot("e1-02-typed-search"))

    # Bonus: an incoming phone call during teaching, declined by the "user".
    call_note = "not attempted"
    try:
        adb("emu", "gsm", "call", "5551234")
        time.sleep(4)
        shots.append(shot("e1-03-incoming-call"))
        n = find(r"(?i).*(decline|reject|dismiss).*")
        if n is not None:
            tap_node(n)
            call_note = "call declined by tapping the phone UI"
        else:
            adb("emu", "gsm", "cancel", "5551234")
            call_note = "call UI button not found; call cancelled from the emulator (no tap to observe)"
        time.sleep(3)
    except Exception as e:
        call_note = f"call simulation failed: {e}"
    log(f"  phone call: {call_note}")
    # Make sure we're back on the search screen with results.
    if find(r"Domino's Pizza") is None:
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)

    tap(r"Domino's Pizza")
    shots.append(shot("e1-04-menu"))
    tap_add_near("Margherita Pizza")
    tap(r"View Cart.*", wait=2.5)
    shots.append(shot("e1-05-cart-while-teaching"))
    tf("stop")
    st = wait_for(lambda s: s.get("pending") is True, timeout=30)
    time.sleep(2)
    shots.append(shot("e1-06-review-learned"))
    saved = tf("save")
    TEACH.update(saved)
    TEACH["call"] = call_note
    ok_steps = all(any(s.startswith(p) for s in saved.get("steps", [])) for p in
                   ["SEARCH {ITEM}", "SELECT RESTAURANT {RESTAURANT}", "ADD {ITEM} TO CART", "STOP_AT_PAYMENT"])
    ok_slots = any(s.startswith("ITEM=") for s in saved.get("slots", [])) and any(s.startswith("RESTAURANT=") for s in saved.get("slots", []))
    checks = [(saved.get("saved") is not None, "skill saved"),
              (ok_slots, "ITEM and RESTAURANT became parameters"),
              (ok_steps, "semantic actions SEARCH {ITEM} → SELECT RESTAURANT → ADD {ITEM} TO CART → STOP_AT_PAYMENT"),
              (not saved.get("warnings"), "no warnings")]
    call_irrelevant = [j for j in saved.get("judged", []) if "IRRELEVANT" in j and ("dialer" in j.lower() or "systemui" in j.lower() or "phone" in j.lower())]
    RESULTS.append({"id": "E1", "mirrors": "T1", "name": "Teach food workflow (+ phone call during teaching)",
                    "pass": all(c for c, _ in checks), "checks": checks, "shots": shots,
                    "evidence": f"{saved.get('saved')} · {saved.get('observed')} observed / {saved.get('relevant')} relevant / {saved.get('ignored')} ignored · phone call: {call_note}"
                                + (f" · discarded as IRRELEVANT: {len(call_irrelevant)} call-UI action(s)" if call_irrelevant else "")})


def scenario(eid, mirrors, name, command, expect, extras=None, answer=None, sign_in=False, timeout=200):
    log(f"{eid} {name}: {command!r}")
    adb("logcat", "-c")
    tf("done")
    before = tf("status").get("runs", 0)
    r = tf("run", command=command)
    shots = []
    if r.get("match") is None:
        log(f"  no skill matched ({'unknown' if r.get('unknown') else r.get('ambiguous')})")
        time.sleep(1)
    else:
        log(f"  matched {r.get('match')} ({r.get('score'):.2f})")
        time.sleep(0.8)
        launch_pf(**(extras or {}))
        time.sleep(3)
        shots.append(shot(f"{eid.lower()}-01-running"))
        answered = set()
        t0 = time.time()
        while time.time() - t0 < timeout:
            st = tf("status")
            if st.get("runs", 0) > before:
                break
            q = st.get("question")
            if q and q["id"] not in answered:
                answered.add(q["id"])
                time.sleep(1)
                shots.append(shot(f"{eid.lower()}-q{len(answered)}-{re.sub('[^a-z]+', '-', q['headline'].lower()).strip('-')[:30]}"))
                a = answer(q) if answer else ("stop",)
                log(f"  question [{q['headline']}] options={q['options']} → {a}")
                if a[0] == "option":
                    tf("answer", option=a[1])
                elif a[0] == "text":
                    tf("answer", text=a[1])
                else:
                    tf("answer")
            elif st.get("boundary") == "AUTH" and st.get("human"):
                time.sleep(1)
                shots.append(shot(f"{eid.lower()}-02-auth-boundary"))
                if sign_in:
                    log("  sign-in screen: the 'user' taps Log in (TeachFlow typed nothing), then Resume")
                    tap(r"Log in")
                    tf("resume")
                else:
                    tf("answer")
            time.sleep(1.2)
        time.sleep(1.5)
        shots.append(shot(f"{eid.lower()}-09-end"))
    runs = runs_json()
    rep = runs[0] if runs and len(runs) > before else {}
    pflog = pf_log()
    checks = expect(rep, pflog)
    checks.append(("PF_PROCEED_TO_PAY" not in pflog and "PF_PAY" not in pflog, "TeachFlow never tapped Proceed to Pay / Pay"))
    phases = [e.get("phase") for e in rep.get("trace", [])]
    RESULTS.append({"id": eid, "mirrors": mirrors, "name": name, "command": command, "pass": bool(rep) and all(c for c, _ in checks),
                    "checks": checks, "shots": shots, "report": rep,
                    "evidence": f"{rep.get('finalStatus', 'no report')} · reason {rep.get('terminalReason')} · steps {rep.get('stepsSucceeded')}/{rep.get('stepsTotal')} · recovered {rep.get('stepsRecovered')} · interventions {rep.get('userInterventions')}",
                    "phases": phases})
    log(f"  → {'PASS' if RESULTS[-1]['pass'] else 'FAIL'} · {RESULTS[-1]['evidence']}")


def has_phase(rep, phase):
    return any(e.get("phase") == phase for e in rep.get("trace", []))


def at_payment(rep, _log):
    return [(rep.get("terminalReason") == "PAYMENT_BOUNDARY" and rep.get("status") == "SUCCESS", "SUCCESS — stopped at payment"),
            (has_phase(rep, "SAFETY STOP"), "trace has SAFETY STOP")]


def main():
    if not setup():
        log("TeachFlow's accessibility service did not connect")
    try:
        teach()
    except Exception:
        log("teaching crashed:\n" + traceback.format_exc())
    tests = [
        ("E2", "T2", "Exact replay", "Order a Margherita pizza from Domino's on PracticeFood", at_payment, None, None, False),
        ("E3", "T3", "Paraphrase", "Get me a margherita from dominos", at_payment, None, None, False),
        ("E4", "T4", "Slot: different item", "Order a Farmhouse pizza from Domino's",
         lambda r, l: at_payment(r, l) + [("PF_ADD Farmhouse Pizza" in l and "PF_ADD Margherita" not in l, "Farmhouse added, not Margherita")], None, None, False),
        ("E5", "T5", "Slot: quantity", "Order two Margherita pizzas from Domino's",
         lambda r, l: at_payment(r, l) + [("PF_QTY Margherita Pizza 2" in l, "quantity reached 2")], None, None, False),
        ("E6", "T6", "Slot: address", "Order a Margherita from Domino's, deliver to work",
         lambda r, l: at_payment(r, l) + [("PF_ADDRESS Work" in l, "Work address selected")], None, None, False),
        ("E7", "T7", "Screen change: promo popup", "Order a Margherita pizza from Domino's on PracticeFood",
         lambda r, l: at_payment(r, l) + [("PF_POPUP_DISMISSED" in l, "popup dismissed via 'Not now'"), ((r.get("stepsRecovered") or 0) >= 1, "recovered step counted")],
         {"popup": True}, None, False),
        ("E8", "T7", "Screen change: item already in cart", "Order a Margherita pizza from Domino's on PracticeFood",
         lambda r, l: at_payment(r, l) + [("PF_ADD Margherita" not in l, "did not add the item again")], {"precart": "Margherita Pizza"}, None, False),
        ("E9", "T10", "Genuinely stuck: item not on the menu", "Order Pepperoni Supreme from Domino's",
         lambda r, l: [(r.get("terminalReason") == "RECOVERY_EXHAUSTED", "RECOVERY_EXHAUSTED after 3 attempts"), (has_phase(r, "RECOVERY EXHAUSTED"), "trace shows RECOVERY EXHAUSTED")],
         None, lambda q: ("stop",), False),
        ("E10", "T11", "Authentication boundary", "Order a Margherita pizza from Domino's on PracticeFood",
         lambda r, l: at_payment(r, l) + [(has_phase(r, "AUTHENTICATION DETECTED") and has_phase(r, "USER RESUMED"), "paused for sign-in, resumed only after Resume"),
                                         ("password_length=0" in l, "TeachFlow never filled the password")], {"login": True}, None, True),
        ("E11", "T12", "Unknown intent", "Book a cab to the airport",
         lambda r, l: [(r.get("terminalReason") == "UNKNOWN_INTENT" and r.get("status") == "NO_OP", "UNKNOWN INTENT, safe no-op")], None, None, False),
        ("E12", "T13", "Ambiguity: 'pizza'", "Order pizza from Domino's",
         lambda r, l: at_payment(r, l) + [(has_phase(r, "PARAMETER RESOLVED"), "asked, then PARAMETER RESOLVED"), ("PF_ADD Farmhouse Pizza" in l, "the chosen pizza (Farmhouse) was added")],
         None, lambda q: ("option", next((i for i, o in enumerate(q["options"]) if "farmhouse" in o.lower()), 0)), False),
        ("E13", "T13", "Missing restaurant, answered by typing", "Order a Margherita",
         lambda r, l: at_payment(r, l) + [(has_phase(r, "PARAMETER RESOLVED"), "asked 'Which restaurant…?', then PARAMETER RESOLVED")],
         None, lambda q: ("text", "Domino's") if q.get("allowText") else ("option", 0), False),
    ]
    for eid, mirrors, name, cmd, expect, extras, answer, sign_in in tests:
        try:
            scenario(eid, mirrors, name, cmd, expect, extras, answer, sign_in)
        except Exception:
            log(f"{eid} crashed:\n" + traceback.format_exc())
            RESULTS.append({"id": eid, "mirrors": mirrors, "name": name, "command": cmd, "pass": False,
                            "checks": [(False, "harness error")], "shots": [], "evidence": "harness error"})
    write_outputs()


def write_outputs():
    with open(os.path.join(OUT, "runs.json"), "w") as f:
        json.dump(runs_json(), f, indent=2)
    with open(os.path.join(OUT, "skills.json"), "w") as f:
        f.write(adb("exec-out", "run-as", TF_PKG, "cat", "files/skills.json"))
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(adb("logcat", "-d", "-s", "TeachFlow:*", "PracticeFood:*"))
    with open(os.path.join(OUT, "harness-log.txt"), "w") as f:
        f.write("\n".join(LOG))
    with open(os.path.join(OUT, "results.json"), "w") as f:
        json.dump([{k: v for k, v in r.items() if k != "report"} for r in RESULTS], f, indent=2, default=str)
    passed = sum(1 for r in RESULTS if r["pass"])
    device = sh("getprop", "ro.product.model").strip() + ", Android " + sh("getprop", "ro.build.version.release").strip()
    md = ["# Emulator end-to-end results", "",
          f"**{passed} / {len(RESULTS)} scenarios passed** · {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())} · emulator: {device}", "",
          "The real TeachFlow debug APK (AccessibilityService + AccessibilityNodeInfo) on an Android emulator, operating **PracticeFood**, a test app built for these tests.",
          "The script plays the person (demonstration taps, commands, answers, signing in). Every result is computed from TeachFlow's own run reports and PracticeFood's log.",
          "**These are not the T1–T14 tests on Zomato / Amazon**, which still require a physical phone. The *Mirrors* column shows which T-test each scenario corresponds to.", "",
          "| ID | Mirrors | Scenario | Result | Evidence |", "|---|---|---|---|---|"]
    for r in RESULTS:
        md.append(f"| {r['id']} | {r['mirrors']} | {r['name']} | {'✅ PASS' if r['pass'] else '❌ FAIL'} | {r['evidence']} |")
    md += ["", "## What was learned (E1)", "",
           f"- Skill: `{TEACH.get('saved')}`",
           f"- Parameters: {', '.join(TEACH.get('slots', []))}",
           f"- Semantic actions: {' → '.join(TEACH.get('steps', []))}",
           f"- Phone call during teaching: {TEACH.get('call')}", "", "Relevance of every observed action:", ""]
    md += [f"- {j}" for j in TEACH.get("judged", [])]
    md += ["", "## Checks per scenario", ""]
    for r in RESULTS:
        md.append(f"### {r['id']} · {r['name']} ({'PASS' if r['pass'] else 'FAIL'})")
        if r.get("command"):
            md.append(f"Command: *{r['command']}*  ")
        md += [f"- {'✅' if ok else '❌'} {desc}" for ok, desc in r["checks"]]
        if r.get("phases"):
            md.append(f"- Trace: {' → '.join(r['phases'])}")
        md += [f"![{s}]({s})" for s in r["shots"]]
        md.append("")
    with open(os.path.join(OUT, "RESULTS.md"), "w") as f:
        f.write("\n".join(md))
    log(f"DONE: {passed}/{len(RESULTS)} passed")


if __name__ == "__main__":
    main()
