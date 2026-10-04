# Auto Bot GGUF

## v4.2 — Dual-SIM AI + WhatsApp calls + App Lock

- 📱 **SIM Dialer page** (menu bar mein) — default call SIM choose karo: SIM 1 / SIM 2 / har baar poochho
- 🧠 **SIM ka intezam (3 taratbeen):** chat mein bola ("call Amir sim 2") > contact ki aadat (bot note karta hai) > default setting. Default set ho to bot poochhta hi nahi — user chat se override kar sakta hai
- 🔊 **Speaker se poochhta hai** — dual-SIM phone par bot TTS se bolta hai "kaun si SIM se call karni hai?" — chat buttons, mic ya text (sim 1 / sim 2 / cancel) se jawab do
- 📊 **Aadat note + default suggestion** — zyada-tar jis SIM se calls hoti hain, bot khud offer karta hai "SIM 2 default banaun?" (user approval se hi)
- 💾 **Pehla save → SIM sawal** — first contact save par bot poochhta hai call kis SIM se karni hogi
- 📞 **Naam ka exact match** — "Rizwan Bai ko call" sirf Rizwan Bai ko lagayega (Amir Bai mix nahi); "amir" akele se sab Amir list ho; "bai" likho to sab Bai wale list
- 💬 **WhatsApp calls** — "wa call Amir" (voice) / "wa video Amir" (video) — data/Wi-Fi se; har saved number par SIM call + WA voice + WA video teeno
- 🔓 **App Lock auto-unlock** — "app lock ka password <pw>" save karo; Accessibility ON ho to bot khud type kar ke lock khol dega, phir kaam (call) poora karega


**DO versions hain** (dono Actions se build hoti hain, "Build APK" ke Artifacts mein):

| | AutoBot LITE (default) | AutoBot PRO |
|---|---|---|
| Python 3.11 (py/pip) | ❌ | ✅ |
| Shell/terminal commands | ✅ | ✅ |
| Contacts save, auto call, WhatsApp | ✅ | ✅ |
| Chat commands (open/search/call/wa...) | ✅ | ✅ |
| Size | ~5MB | ~70MB |
| Low-RAM phones (Redmi 9C etc) | ✅ stable | ⚠️ OOM risk |

- **LITE** = kam RAM wale phone ke liye (purani app ki jagah yehi lagegi — same app id)
- **PRO** = "Auto Bot Pro" naam se alag app (zyada RAM wale phone ke liye, Python built-in)

## Features (dono mein)
- 💾 Contact book mein naam + number save (phone ki asli contacts mein)
- 📞 Auto call — dialer bina khole direct call (CALL_PHONE permission)
- 💬 WhatsApp chat kholna
- 🖥 Built-in terminal (real Android shell: ls, mkdir, cat, ps, df...) — 'py' sirf PRO mein
- 🌐 Auto Bot dashboard kholna (URL app mein save hota hai)
- ⚠️ Crash report: crash hone par agli baar app khulte hi log dikhta hai + server pe jata hai

## APK Build
GitHub khud build karta hai (Actions). Har push par:
1. Repo ke *Actions* tab kholo
2. Latest "Build APK" run kholo
3. *Artifacts* se download karo:
   - `AutoBot-LITE-APK` → AutoBot-Lite-v2.1.apk (pehle ye try karo)
   - `AutoBot-PRO-APK` → AutoBot-Pro-v2.1.apk (Python chahiye ho to)
4. Phone mein install karo ("unknown sources" allow kar ke)

## Permissions (install par)
- CALL_PHONE: direct call ke liye
- WRITE_CONTACTS / READ_CONTACTS: contacts save aur duplicate check
- INTERNET

## Note
Release-signed APK hai (dono same key se). Play Store upload ke liye bhi yehi key use hogi.
