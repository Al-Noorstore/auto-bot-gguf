# Auto Bot GGUF

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
