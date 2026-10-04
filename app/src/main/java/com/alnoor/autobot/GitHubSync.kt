package com.alnoor.autobot

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Project → GitHub push (large files via Releases when needed) + APK/AAB workflows.
 */
object GitHubSync {

    private const val API = "https://api.github.com"
    /** Contents API practical limit (~25MB decoded); larger → Release asset */
    private const val CONTENTS_MAX = 20L * 1024 * 1024
    private const val MAX_FILES = 200

    private val skipNames = setOf(
        ".git", "build", ".gradle", "node_modules", ".idea", "captures",
        "local.properties", ".DS_Store", "__MACOSX", "apk", "release"
    )

    fun hdr(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28"
    )

    fun login(token: String): String? {
        val (c, t) = TokenVault.http("GET", "$API/user", hdr(token), null)
        if (c !in 200..299) return null
        return try { JSONObject(t).optString("login").ifBlank { null } } catch (_: Exception) { null }
    }

    fun ensureRepo(token: String, repoName: String, privateRepo: Boolean = true): Pair<Boolean, String> {
        val user = login(token) ?: return false to "❌ GitHub login fail — token (repo + workflow) check karo."
        val (c0, _) = TokenVault.http("GET", "$API/repos/$user/$repoName", hdr(token), null)
        if (c0 in 200..299) return true to "$user/$repoName"
        val body = JSONObject().put("name", repoName).put("private", privateRepo).put("auto_init", true).toString()
        val (c, t) = TokenVault.http("POST", "$API/user/repos", hdr(token), body)
        return if (c in 200..299) true to "$user/$repoName"
        else false to "❌ Repo create fail HTTP $c: ${t.take(250)}"
    }

    fun putFile(token: String, fullRepo: String, path: String, content: ByteArray, message: String): String {
        if (content.size > CONTENTS_MAX) return "LARGE"
        val b64 = Base64.encodeToString(content, Base64.NO_WRAP)
        val (gc, gt) = TokenVault.http("GET", "$API/repos/$fullRepo/contents/${path.trimStart('/')}", hdr(token), null)
        val sha = if (gc in 200..299) try { JSONObject(gt).optString("sha", "") } catch (_: Exception) { "" } else ""
        val body = JSONObject().put("message", message).put("content", b64)
        if (sha.isNotBlank()) body.put("sha", sha)
        val (c, t) = TokenVault.http("PUT", "$API/repos/$fullRepo/contents/${path.trimStart('/')}", hdr(token), body.toString())
        return if (c in 200..299) "OK" else "FAIL HTTP $c ${t.take(150)}"
    }

    /** Large file → GitHub Release asset */
    fun uploadReleaseAsset(token: String, fullRepo: String, tag: String, file: File, relName: String): String {
        // create or get release
        var uploadUrl: String? = null
        val (gc, gt) = TokenVault.http("GET", "$API/repos/$fullRepo/releases/tags/$tag", hdr(token), null)
        if (gc in 200..299) {
            uploadUrl = try { JSONObject(gt).optString("upload_url", "").substringBefore("{") } catch (_: Exception) { null }
        } else {
            val body = JSONObject()
                .put("tag_name", tag)
                .put("name", "Auto Bot assets")
                .put("body", "Large files from Auto Bot")
                .toString()
            val (c, t) = TokenVault.http("POST", "$API/repos/$fullRepo/releases", hdr(token), body)
            if (c in 200..299) {
                uploadUrl = try { JSONObject(t).optString("upload_url", "").substringBefore("{") } catch (_: Exception) { null }
            } else return "FAIL release HTTP $c ${t.take(120)}"
        }
        if (uploadUrl.isNullOrBlank()) return "FAIL no upload_url"
        val url = "$uploadUrl?name=" + java.net.URLEncoder.encode(relName.replace("/", "_"), "UTF-8")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 60000
            conn.readTimeout = 300000
            file.inputStream().use { inp -> conn.outputStream.use { out -> inp.copyTo(out) } }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) "OK-RELEASE" else "FAIL upload HTTP $code"
        } catch (e: Exception) {
            "FAIL ${e.message}"
        }
    }

    fun listProjectFiles(root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        fun walk(dir: File, rel: String) {
            if (out.size >= MAX_FILES) return
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (out.size >= MAX_FILES) return
                if (f.name in skipNames) continue
                if (f.name.startsWith(".") && f.name != ".github") continue
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) {
                    if (f.name == ".github") walk(f, r)
                    else walk(f, r)
                } else if (f.isFile && f.length() > 0) {
                    out.add(r to f)
                }
            }
        }
        walk(root, "")
        return out
    }

    data class PushResult(
        val ok: Int,
        val fail: Int,
        val large: Int,
        val errors: List<String>,
        val message: String
    )

    fun pushFolder(token: String, fullRepo: String, root: File, commitPrefix: String = "Auto Bot sync"): PushResult {
        if (!root.isDirectory) {
            return PushResult(0, 0, 0, listOf("not a dir"), "❌ Folder nahi: ${root.absolutePath}")
        }
        val files = listProjectFiles(root)
        if (files.isEmpty()) {
            return PushResult(0, 0, 0, emptyList(), "❌ Folder khali. ${root.absolutePath}")
        }
        var ok = 0
        var fail = 0
        var large = 0
        val errors = mutableListOf<String>()
        for ((rel, f) in files) {
            if (f.length() > CONTENTS_MAX) {
                val r = uploadReleaseAsset(token, fullRepo, "autobot-assets", f, rel)
                if (r.startsWith("OK")) { ok++; large++ }
                else {
                    fail++
                    if (errors.size < 5) errors.add("$rel (${f.length() / 1024}KB) → $r")
                }
            } else {
                val res = putFile(token, fullRepo, rel, f.readBytes(), "$commitPrefix: $rel")
                if (res == "OK") ok++
                else {
                    fail++
                    if (errors.size < 5) errors.add("$rel → $res")
                }
            }
        }
        val msg = buildString {
            append("📤 Push: $ok OK")
            if (large > 0) append(" (large via Release: $large)")
            if (fail > 0) append(", $fail fail")
            append("\nRepo: https://github.com/$fullRepo")
            append("\nSource: ${root.absolutePath}")
            if (errors.isNotEmpty()) append("\n⚠️ Errors:\n" + errors.joinToString("\n"))
            if (files.size >= MAX_FILES) append("\n⚠️ $MAX_FILES files limit — baqi next push.")
        }
        return PushResult(ok, fail, large, errors, msg)
    }

    fun unzipTo(zipPath: String, dest: File): String {
        val z = File(zipPath)
        if (!z.isFile) return "❌ Zip nahi: $zipPath"
        dest.mkdirs()
        return try {
            ZipFile(z).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name.replace("\\", "/")
                    if (name.contains("..")) continue
                    val out = File(dest, name)
                    if (e.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(e).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                    }
                }
            }
            "✅ Unzip → ${dest.absolutePath}"
        } catch (e: Exception) {
            "❌ Unzip: ${e.message}"
        }
    }

    fun androidWorkflowYaml(withAab: Boolean): String {
        val aabSteps = if (withAab) """
      - name: Build Release Bundle (AAB)
        run: |
          if [ -f gradlew ]; then ./gradlew bundleRelease --stacktrace || ./gradlew bundleDebug --stacktrace
          else echo "gradlew missing"; exit 1
          fi
      - name: Upload AAB
        uses: actions/upload-artifact@v4
        with:
          name: app-aab
          path: |
            app/build/outputs/bundle/**/*.aab
            **/build/outputs/bundle/**/*.aab
""" else ""
        return """
name: Android CI
on:
  workflow_dispatch:
    inputs:
      build_aab:
        description: 'Also build AAB'
        required: false
        default: 'false'
  push:
    branches: [ main, master ]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - run: chmod +x gradlew || true
      - name: Build Debug APK
        run: |
          if [ -f gradlew ]; then ./gradlew assembleDebug --stacktrace
          else echo "gradlew missing — Android project required"; exit 1
          fi
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: |
            app/build/outputs/apk/**/*.apk
            **/build/outputs/apk/**/*.apk
$aabSteps
""".trimIndent() + "\n"
    }


    /** Windows EXE — detects Electron / .NET / Python / Go */
    fun windowsExeWorkflowYaml(): String = """
name: Windows EXE CI
on:
  workflow_dispatch:
  push:
    branches: [ main, master ]
jobs:
  build-exe:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4

      - name: Detect & build EXE
        shell: pwsh
        run: |
          $ErrorActionPreference = "Stop"
          New-Item -ItemType Directory -Force -Path out | Out-Null

          if (Test-Path "package.json") {
            Write-Host "Node/Electron project"
            npm ci
            if (Select-String -Path package.json -Pattern "electron" -Quiet) {
              npx --yes electron-builder --win --dir
              Get-ChildItem -Recurse -Filter *.exe | Copy-Item -Destination out -ErrorAction SilentlyContinue
            } else {
              npm run build --if-present
              npx --yes pkg . --targets node18-win-x64 --out-path out 2>$null
              Get-ChildItem -Recurse -Filter *.exe | Copy-Item -Destination out -ErrorAction SilentlyContinue
            }
          }
          elseif (Get-ChildItem -Recurse -Filter *.csproj | Select-Object -First 1) {
            Write-Host ".NET project"
            $proj = (Get-ChildItem -Recurse -Filter *.csproj | Select-Object -First 1).FullName
            dotnet publish $proj -c Release -r win-x64 --self-contained true -o out
          }
          elseif ((Test-Path "main.py") -or (Test-Path "app.py") -or (Test-Path "requirements.txt")) {
            Write-Host "Python project"
            python -m pip install --upgrade pip
            if (Test-Path "requirements.txt") { pip install -r requirements.txt }
            pip install pyinstaller
            $entry = "main.py"
            if (Test-Path "app.py") { $entry = "app.py" }
            pyinstaller --noconfirm --clean -F $entry --distpath out --workpath build_py --specpath build_py
          }
          elseif (Test-Path "go.mod") {
            Write-Host "Go project"
            go build -o out/app.exe .
          }
          else {
            Write-Host "No known project type (need package.json / .csproj / main.py / go.mod)"
            exit 1
          }

          $exes = Get-ChildItem -Path out -Filter *.exe -Recurse -ErrorAction SilentlyContinue
          if (-not $exes) { $exes = Get-ChildItem -Recurse -Filter *.exe -ErrorAction SilentlyContinue | Select-Object -First 5 }
          if (-not $exes) { Write-Host "No .exe produced"; exit 1 }
          Write-Host "Built:" $exes.FullName

      - name: Upload EXE
        uses: actions/upload-artifact@v4
        with:
          name: windows-exe
          path: |
            out/**/*.exe
            **/*.exe
"""


    /** iOS / iPhone IPA — macOS runner; Xcode / Flutter / RN (signing optional via secrets) */
    fun iosIpaWorkflowYaml(): String = """
name: iOS IPA CI
on:
  workflow_dispatch:
  push:
    branches: [ main, master ]
jobs:
  build-ipa:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4

      - name: Select Xcode
        run: sudo xcode-select -s /Applications/Xcode.app || true

      # Optional Apple signing — set these GitHub Secrets on the repo:
      # IOS_CERTIFICATE_BASE64  = .p12 cert base64
      # IOS_CERTIFICATE_PASSWORD = p12 password
      # IOS_PROVISION_PROFILE_BASE64 = .mobileprovision base64
      # IOS_TEAM_ID = Apple Team ID (optional)
      - name: Install Apple certificate (if secrets set)
        env:
          IOS_CERTIFICATE_BASE64: ${{ secrets.IOS_CERTIFICATE_BASE64 }}
          IOS_CERTIFICATE_PASSWORD: ${{ secrets.IOS_CERTIFICATE_PASSWORD }}
          IOS_PROVISION_PROFILE_BASE64: ${{ secrets.IOS_PROVISION_PROFILE_BASE64 }}
          KEYCHAIN_PASSWORD: ${{ secrets.IOS_KEYCHAIN_PASSWORD || 'autobot-temp' }}
        run: |
          set -e
          if [ -z "$IOS_CERTIFICATE_BASE64" ] || [ -z "$IOS_PROVISION_PROFILE_BASE64" ]; then
            echo "SIGNING=0" >> $GITHUB_ENV
            echo "No signing secrets — unsigned/build-only mode"
            exit 0
          fi
          echo "SIGNING=1" >> $GITHUB_ENV
          CERTIFICATE_PATH=$RUNNER_TEMP/build_certificate.p12
          PP_PATH=$RUNNER_TEMP/build.mobileprovision
          KEYCHAIN_PATH=$RUNNER_TEMP/app-signing.keychain-db
          echo -n "$IOS_CERTIFICATE_BASE64" | base64 --decode -o $CERTIFICATE_PATH
          echo -n "$IOS_PROVISION_PROFILE_BASE64" | base64 --decode -o $PP_PATH
          security create-keychain -p "$KEYCHAIN_PASSWORD" $KEYCHAIN_PATH
          security set-keychain-settings -lut 21600 $KEYCHAIN_PATH
          security unlock-keychain -p "$KEYCHAIN_PASSWORD" $KEYCHAIN_PATH
          security import $CERTIFICATE_PATH -P "$IOS_CERTIFICATE_PASSWORD" -A -t cert -f pkcs12 -k $KEYCHAIN_PATH
          security list-keychain -d user -s $KEYCHAIN_PATH
          security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" $KEYCHAIN_PATH
          mkdir -p ~/Library/MobileDevice/Provisioning\ Profiles
          PROFILE_ID=$(/usr/libexec/PlistBuddy -c 'Print UUID' /dev/stdin <<< $(security cms -D -i $PP_PATH))
          cp $PP_PATH ~/Library/MobileDevice/Provisioning\ Profiles/$PROFILE_ID.mobileprovision
          echo "PROFILE_UUID=$PROFILE_ID" >> $GITHUB_ENV
          echo "Signing certificate + provision installed"

      - name: Detect & build iOS
        env:
          IOS_TEAM_ID: ${{ secrets.IOS_TEAM_ID }}
        run: |
          set -e
          mkdir -p out
          SIGN_ARGS="CODE_SIGNING_ALLOWED=NO"
          if [ "${SIGNING:-0}" = "1" ]; then
            SIGN_ARGS="CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Manual"
            if [ -n "$IOS_TEAM_ID" ]; then SIGN_ARGS="$SIGN_ARGS DEVELOPMENT_TEAM=$IOS_TEAM_ID"; fi
            if [ -n "${PROFILE_UUID:-}" ]; then SIGN_ARGS="$SIGN_ARGS PROVISIONING_PROFILE=$PROFILE_UUID"; fi
          fi

          if [ -f "pubspec.yaml" ] && [ -d "ios" ]; then
            echo "Flutter iOS"
            if ! command -v flutter >/dev/null; then
              git clone https://github.com/flutter/flutter.git -b stable --depth 1 $HOME/flutter
              export PATH="$HOME/flutter/bin:$PATH"
            fi
            flutter pub get
            if [ "${SIGNING:-0}" = "1" ]; then
              flutter build ipa --release || flutter build ios --release
            else
              flutter build ios --release --no-codesign
            fi
            if [ -d "build/ios/iphoneos" ]; then
              cd build/ios/iphoneos
              APP=$(ls -d *.app 2>/dev/null | head -1)
              if [ -n "$APP" ]; then
                mkdir -p Payload && cp -r "$APP" Payload/
                zip -r ../../../../out/app.ipa Payload
              fi
              cd -
            fi
            cp -f build/ios/ipa/*.ipa out/ 2>/dev/null || true

          elif [ -f "package.json" ] && [ -d "ios" ]; then
            echo "RN / Capacitor iOS"
            npm ci || npm install
            cd ios
            [ -f Podfile ] && (pod install --repo-update || pod install)
            WORKSPACE=$(ls -d *.xcworkspace 2>/dev/null | head -1)
            PROJECT=$(ls -d *.xcodeproj 2>/dev/null | head -1)
            SCHEME=$(xcodebuild -list ${WORKSPACE:+-workspace "$WORKSPACE"} ${PROJECT:+-project "$PROJECT"} 2>/dev/null | awk '/Schemes:/{f=1;next} f&&NF{print $1; exit}')
            SCHEME=${SCHEME:-Runner}
            if [ -n "$WORKSPACE" ]; then
              xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -configuration Release -sdk iphoneos -derivedDataPath build $SIGN_ARGS
            else
              xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration Release -sdk iphoneos -derivedDataPath build $SIGN_ARGS
            fi
            APP=$(find build -name "*.app" | head -1)
            if [ -n "$APP" ]; then
              mkdir -p Payload && cp -r "$APP" Payload/ && zip -r ../out/app.ipa Payload
            fi
            cd ..

          elif ls *.xcodeproj >/dev/null 2>&1 || ls *.xcworkspace >/dev/null 2>&1; then
            echo "Native Xcode"
            WORKSPACE=$(ls -d *.xcworkspace 2>/dev/null | head -1)
            PROJECT=$(ls -d *.xcodeproj 2>/dev/null | head -1)
            if [ -n "$WORKSPACE" ]; then
              SCHEME=$(xcodebuild -list -workspace "$WORKSPACE" | awk '/Schemes:/{f=1;next} f&&NF{print $1; exit}')
              xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -configuration Release -sdk iphoneos -derivedDataPath build $SIGN_ARGS
            else
              SCHEME=$(xcodebuild -list -project "$PROJECT" | awk '/Schemes:/{f=1;next} f&&NF{print $1; exit}')
              xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration Release -sdk iphoneos -derivedDataPath build $SIGN_ARGS
            fi
            APP=$(find build -name "*.app" 2>/dev/null | head -1)
            if [ -n "$APP" ]; then
              mkdir -p Payload && cp -r "$APP" Payload/ && zip -r out/app.ipa Payload
            fi
          else
            echo "No iOS project found"
            exit 1
          fi

          if [ -z "$(ls out/*.ipa 2>/dev/null)" ]; then
            APP=$(find . -name "*.app" 2>/dev/null | head -1)
            if [ -n "$APP" ]; then
              ditto -c -k --keepParent "$APP" out/app-bundle.zip || zip -r out/app-bundle.zip "$APP"
            else
              echo "Build produced no IPA/app"
              exit 1
            fi
          fi
          ls -la out/

      - name: Upload IPA
        uses: actions/upload-artifact@v4
        with:
          name: ios-ipa
          path: |
            out/**/*.ipa
            out/**/*.zip
"""


    fun fastfileIosTemplate(): String = """
default_platform(:ios)

platform :ios do
  desc "Build IPA (CI)"
  lane :build do
    setup_ci if ENV["CI"]
    match(type: "appstore", readonly: true) if ENV["MATCH_GIT_URL"]
    gym(
      scheme: ENV.fetch("IOS_SCHEME", "Runner"),
      export_method: ENV.fetch("IOS_EXPORT_METHOD", "app-store"),
      output_directory: "out",
      output_name: "app",
      clean: true,
      skip_codesigning: ENV["IOS_CERTIFICATE_BASE64"].to_s.empty?
    )
  end

  desc "Upload to TestFlight"
  lane :beta do
    build
    pilot(skip_waiting_for_build_processing: true) if ENV["APP_STORE_CONNECT_API_KEY_PATH"] || ENV["FASTLANE_USER"]
  end
end
""".trimIndent() + "\n"

    fun fastfileAndroidTemplate(): String = """
default_platform(:android)

platform :android do
  desc "Build debug APK"
  lane :apk do
    gradle(task: "assembleDebug")
    APK = Dir["app/build/outputs/apk/**/*.apk"].first
    UI.user_error!("APK not found") unless APK
    sh "mkdir -p out && cp '#{APK}' out/"
  end

  desc "Build release AAB"
  lane :aab do
    gradle(task: "bundleRelease")
    AAB = Dir["app/build/outputs/bundle/**/*.aab"].first
    UI.user_error!("AAB not found") unless AAB
    sh "mkdir -p out && cp '#{AAB}' out/"
  end

  desc "Upload AAB to Play (internal)"
  lane :play do
    aab
    upload_to_play_store(
      track: "internal",
      aab: Dir["out/*.aab"].first,
      json_key_data: ENV["PLAY_STORE_JSON_KEY"]
    ) if ENV["PLAY_STORE_JSON_KEY"]
  end
end
""".trimIndent() + "\n"

    fun gemfileTemplate(): String = """
source "https://rubygems.org"
gem "fastlane"
""".trimIndent() + "\n"

    fun fastlaneWorkflowYaml(platform: String): String {
        val isIos = platform == "ios"
        return if (isIos) """
name: Fastlane iOS
on:
  workflow_dispatch:
  push:
    branches: [ main, master ]
jobs:
  fastlane-ios:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: ruby/setup-ruby@v1
        with:
          ruby-version: "3.2"
          bundler-cache: true
      - name: Install Apple cert (optional secrets)
        env:
          IOS_CERTIFICATE_BASE64: ${{ secrets.IOS_CERTIFICATE_BASE64 }}
          IOS_CERTIFICATE_PASSWORD: ${{ secrets.IOS_CERTIFICATE_PASSWORD }}
          IOS_PROVISION_PROFILE_BASE64: ${{ secrets.IOS_PROVISION_PROFILE_BASE64 }}
          KEYCHAIN_PASSWORD: ${{ secrets.IOS_KEYCHAIN_PASSWORD || 'autobot-temp' }}
        run: |
          if [ -z "$IOS_CERTIFICATE_BASE64" ]; then echo "No cert secrets"; exit 0; fi
          CERT=$RUNNER_TEMP/cert.p12
          PP=$RUNNER_TEMP/profile.mobileprovision
          KC=$RUNNER_TEMP/signing.keychain-db
          echo -n "$IOS_CERTIFICATE_BASE64" | base64 --decode -o $CERT
          echo -n "$IOS_PROVISION_PROFILE_BASE64" | base64 --decode -o $PP
          security create-keychain -p "$KEYCHAIN_PASSWORD" $KC
          security set-keychain-settings -lut 21600 $KC
          security unlock-keychain -p "$KEYCHAIN_PASSWORD" $KC
          security import $CERT -P "$IOS_CERTIFICATE_PASSWORD" -A -t cert -f pkcs12 -k $KC
          security list-keychain -d user -s $KC
          security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" $KC
          mkdir -p ~/Library/MobileDevice/Provisioning\ Profiles
          UUID=$(/usr/libexec/PlistBuddy -c 'Print UUID' /dev/stdin <<< $(security cms -D -i $PP))
          cp $PP ~/Library/MobileDevice/Provisioning\ Profiles/$UUID.mobileprovision
      - name: Fastlane build
        env:
          IOS_SCHEME: ${{ secrets.IOS_SCHEME || 'Runner' }}
          IOS_EXPORT_METHOD: ${{ secrets.IOS_EXPORT_METHOD || 'app-store' }}
          APP_STORE_CONNECT_API_KEY_ID: ${{ secrets.APP_STORE_CONNECT_API_KEY_ID }}
          APP_STORE_CONNECT_API_ISSUER_ID: ${{ secrets.APP_STORE_CONNECT_API_ISSUER_ID }}
          APP_STORE_CONNECT_API_KEY_PATH: ${{ secrets.APP_STORE_CONNECT_API_KEY_PATH }}
        run: |
          gem install bundler
          bundle install
          bundle exec fastlane ios build
      - uses: actions/upload-artifact@v4
        with:
          name: fastlane-ios
          path: |
            out/**/*.ipa
            **/*.ipa
""" else """
name: Fastlane Android
on:
  workflow_dispatch:
  push:
    branches: [ main, master ]
jobs:
  fastlane-android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: ruby/setup-ruby@v1
        with:
          ruby-version: "3.2"
          bundler-cache: true
      - run: chmod +x gradlew || true
      - name: Fastlane APK
        run: |
          gem install bundler
          bundle install || gem install fastlane
          if [ -f fastlane/Fastfile ]; then
            bundle exec fastlane android apk || fastlane android apk
          else
            ./gradlew assembleDebug
            mkdir -p out && cp app/build/outputs/apk/**/*.apk out/ 2>/dev/null || true
          fi
      - uses: actions/upload-artifact@v4
        with:
          name: fastlane-android
          path: |
            out/**/*.apk
            out/**/*.aab
            app/build/outputs/**/*.apk
            app/build/outputs/**/*.aab
"""
    }

    fun ensureFastlane(token: String, fullRepo: String, platform: String): String {
        val sb = StringBuilder()
        // Gemfile
        var r = putFile(token, fullRepo, "Gemfile", gemfileTemplate().toByteArray(), "Add Gemfile for Fastlane")
        sb.append("Gemfile: $r\n")
        r = putFile(token, fullRepo, "fastlane/Fastfile",
            (if (platform == "ios") fastfileIosTemplate() else fastfileAndroidTemplate()).toByteArray(),
            "Add Fastfile ($platform)")
        sb.append("Fastfile: $r\n")
        // Appfile placeholder
        val appfile = if (platform == "ios")
            "app_identifier(\"com.example.app\")\n# team_id(\"XXXXXXXX\")\n"
        else
            "json_key_file(\"\") # or use PLAY_STORE_JSON_KEY secret\npackage_name(\"com.example.app\")\n"
        r = putFile(token, fullRepo, "fastlane/Appfile", appfile.toByteArray(), "Add Appfile")
        sb.append("Appfile: $r\n")
        val wfPath = if (platform == "ios") ".github/workflows/fastlane-ios.yml" else ".github/workflows/fastlane-android.yml"
        r = putFile(token, fullRepo, wfPath, fastlaneWorkflowYaml(platform).toByteArray(), "Add Fastlane workflow")
        sb.append("Workflow: $r")
        return sb.toString()
    }

    fun triggerFastlane(token: String, fullRepo: String, platform: String): String {
        val f = if (platform == "ios") "fastlane-ios.yml" else "fastlane-android.yml"
        return triggerWorkflow(token, fullRepo, f)
    }

    fun ensureIosWorkflow(token: String, fullRepo: String): String {
        val path = ".github/workflows/ios-ipa-ci.yml"
        val res = putFile(token, fullRepo, path, iosIpaWorkflowYaml().toByteArray(), "Add iOS IPA CI")
        return if (res == "OK") "✅ iOS workflow: $path" else "⚠️ iOS workflow: $res"
    }

    fun triggerIosWorkflow(token: String, fullRepo: String): String =
        triggerWorkflow(token, fullRepo, "ios-ipa-ci.yml")

    fun ensureWindowsWorkflow(token: String, fullRepo: String): String {
        val path = ".github/workflows/windows-exe-ci.yml"
        val res = putFile(token, fullRepo, path, windowsExeWorkflowYaml().toByteArray(), "Add Windows EXE CI")
        return if (res == "OK") "✅ Windows workflow: $path" else "⚠️ Windows workflow: $res"
    }

    fun triggerWindowsWorkflow(token: String, fullRepo: String): String =
        triggerWorkflow(token, fullRepo, "windows-exe-ci.yml")

    fun ensureAndroidWorkflow(token: String, fullRepo: String, withAab: Boolean = false): String {
        val path = ".github/workflows/android-ci.yml"
        val res = putFile(token, fullRepo, path, androidWorkflowYaml(withAab).toByteArray(), "Android CI APK/AAB")
        return if (res == "OK") "✅ Workflow updated ($path)" else "⚠️ Workflow: $res"
    }

    fun triggerWorkflow(token: String, fullRepo: String, workflowFile: String = "android-ci.yml"): String {
        fun tryRef(ref: String): Pair<Int, String> {
            val url = "$API/repos/$fullRepo/actions/workflows/$workflowFile/dispatches"
            val body = JSONObject().put("ref", ref).toString()
            return TokenVault.http("POST", url, hdr(token), body)
        }
        var (c, t) = tryRef("main")
        if (c !in 200..299 && c != 204) {
            val r = tryRef("master")
            c = r.first; t = r.second
        }
        return if (c in 200..299 || c == 204)
            "✅ Build start — https://github.com/$fullRepo/actions"
        else "❌ Workflow HTTP $c: ${t.take(250)}"
    }

    /** Latest successful artifact download URLs (API) */
    fun latestArtifacts(token: String, fullRepo: String): List<Triple<String, Long, String>> {
        // name, id, archive_download_url
        val (c, t) = TokenVault.http("GET", "$API/repos/$fullRepo/actions/artifacts?per_page=10", hdr(token), null)
        if (c !in 200..299) return emptyList()
        val arr = try { JSONObject(t).optJSONArray("artifacts") ?: JSONArray() } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Triple<String, Long, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("expired")) continue
            out.add(
                Triple(
                    o.optString("name"),
                    o.optLong("id"),
                    o.optString("archive_download_url")
                )
            )
        }
        return out
    }

    fun downloadArtifactZip(token: String, archiveUrl: String, destZip: File): String {
        return try {
            val conn = URL(archiveUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 30000
            conn.readTimeout = 300000
            if (conn.responseCode !in 200..299) return "❌ Download HTTP ${conn.responseCode}"
            destZip.parentFile?.mkdirs()
            conn.inputStream.use { inp -> FileOutputStream(destZip).use { out -> inp.copyTo(out) } }
            "✅ ${destZip.absolutePath}"
        } catch (e: Exception) {
            "❌ ${e.message}"
        }
    }

    /** Extract first apk/aab from artifact zip into outDir */
    fun extractBuildProduct(zipFile: File, outDir: File): List<File> {
        outDir.mkdirs()
        val found = mutableListOf<File>()
        try {
            ZipFile(zipFile).use { zip ->
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val n = e.name.lowercase()
                    if (e.isDirectory) continue
                    if (n.endsWith(".apk") || n.endsWith(".aab") || n.endsWith(".exe") || n.endsWith(".ipa")) {
                        val name = File(e.name).name
                        val out = File(outDir, name)
                        zip.getInputStream(e).use { inp -> FileOutputStream(out).use { inp.copyTo(it) } }
                        found.add(out)
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    fun activeProjectDir(ctx: Context): File? {
        val p = ProjectStore.active(ctx) ?: return null
        val f = File(p.path)
        return if (f.isDirectory) f else null
    }

    fun safeRepoName(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { "autobot-project" }

    // pending error approval
    fun setPendingError(ctx: Context, text: String) {
        ctx.getSharedPreferences("autobot_memory", Context.MODE_PRIVATE).edit()
            .putString("gh_pending_error", text).apply()
    }

    fun getPendingError(ctx: Context): String? =
        ctx.getSharedPreferences("autobot_memory", Context.MODE_PRIVATE).getString("gh_pending_error", null)

    fun clearPendingError(ctx: Context) {
        ctx.getSharedPreferences("autobot_memory", Context.MODE_PRIVATE).edit()
            .remove("gh_pending_error").apply()
    }
}
