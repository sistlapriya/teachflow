# Submission checklist

Status as of this repository version. ✅ = done and verified here · ⏳ = needs your action · ❌ = not done.

| | Item | Status | Notes |
|---|---|---|---|
| ⏳ | Installable APK | Source ready; **not built here** | Build in Android Studio (README §4). Fix any first-build Compose errors. Attach `app-debug.apk` to a GitHub release. |
| ⏳ | Public/shared GitHub | Repository prepared | Push this folder. Fill `DATA.github` in `web/index.html` and the README Team section. |
| ✅ | README | Done | Problem, solution, architecture, setup, permissions, teach, replay, parameters, recovery, safety, target apps, limitations, testing, demo, team (team to fill) |
| ✅ | Setup instructions | Done | Including the Android 13+ restricted-settings step |
| ⏳ | Demo video ≤ 5 min | Script ready | `demo/demo-script.md`. Record on a device. |
| ⏳ | Presentation PDF/PPT | Content ready | `docs/presentation.md`. Build the deck; slide 12 only after device results exist. |
| ✅ | Architecture diagram | Done | `docs/architecture.md` (Mermaid) + website section |
| ✅ | Target apps declared | Done | Zomato (primary), Amazon (secondary), cross-app EXPERIMENTAL |
| ✅ | Known limitations | Done | README §12, website "What's real", slide 13 |
| ❌ | T1–T14 tested | **NOT YET TESTED** | Needs a physical Android device (`docs/testing.md`) |
| ❌ | Metrics populated from real runs | **NOT YET TESTED** | Automatic once the APK export is imported or published |
| ✅ | No credentials committed | Checked | `docs/audit.md` §secret scan |
| ✅ | No fake claims | Checked | All device results say NOT YET TESTED; simulations are labelled |
