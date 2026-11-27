# GameShare ইউজার ম্যানুয়াল (বাংলা)

GameShare দিয়ে একটা Android ফোনের মোবাইল ইন্টারনেট (ডাটা) Wi-Fi Direct-এর মাধ্যমে কাছের অন্য Android ফোনের সাথে ভাগ করা যায়। এটা বিশেষভাবে অনলাইন গেম, ভয়েস চ্যাট আর ডাউনলোডের জন্য বানানো হয়েছে। কোনো root লাগে না।

এই ম্যানুয়ালে দুটো ভূমিকা আছে:

- **Host (হোস্ট):** যে ফোনে মোবাইল ডাটা চালু থাকে এবং GameShare চালানো হয়।
- **Client (ক্লায়েন্ট):** যে ফোন হোস্টের ইন্টারনেট ব্যবহার করবে।

> মেনুর নাম আর বোতামের লেখা ফোনের brand ও Android version অনুযায়ী সামান্য আলাদা হতে পারে। যেমন Huawei/EMUI ফোনে "Wi-Fi Direct" মেনু অন্য জায়গায় থাকতে পারে।

---

## ১. কী কী লাগবে

| জিনিস | কী দরকার |
|---|---|
| Host ফোন | Android 10 বা তার উপরে (ভালো কাজ করার জন্য সুপারিশ), সক্রিয় মোবাইল ডাটা, GameShare APK |
| Client ফোন | Wi-Fi Direct সাপোর্ট (প্রায় সব Android ফোনে আছে), এবং SOCKS5 সাপোর্ট করে এমন একটা app |
| Huawei Y5 Prime 2018 (Android 8.1) | Client হিসেবে ভালো কাজ করে। Host হিসেবে নিশ্চিত নয় (নিচে দেখুন) |

**গুরুত্বপূর্ণ:** Host ফোনের মোবাইল ডাটা চালু থাকতে হবে। সেই ডাটা প্যাকেজ থেকেই Client-রা ইন্টারনেট ব্যবহার করবে, তাই ডাটা দ্রুত শেষ হতে পারে।

---

## ২. APK ইনস্টল করা

1. ফোনের browser-এ এই লিংক খুলুন:
   `https://github.com/chainatelapoka-hue/gameshare/releases/latest/download/GameShare.apk`
2. Download শেষ হলে ফাইলটা খুলুন।
3. যদি বলে "Unknown apps"-এর অনুমতি লাগবে, তাহলে browser-কে অনুমতি দিন।
4. Install শেষ হলে GameShare খুলুন।

**Update করতে চাইলে:** নতুন APK-র আগে পুরনো GameShare uninstall করুন। প্রতিটা build-এর signing key আলাদা হতে পারে, তাই সরাসরি update না হওয়াও স্বাভাবিক।

---

## ৩. Host ফোনে শেয়ারিং চালু করা

1. Host ফোনে মোবাইল ডাটা চালু করুন।
2. GameShare খুলুন এবং অনুমতি দিন:
   - **Location (অবস্থান):** Wi-Fi Direct-এর জন্য লাগে। Android 13 বা তার উপরে এর বদলে "Nearby Wi-Fi devices" অনুমতি চাওয়া হবে।
   - **Notification:** না দিলেও শেয়ারিং চলবে, শুধু notification দেখাবে না।
3. **Start Sharing** বোতাম চাপুন।
4. Status-এ **Waiting for devices** দেখালে কয়েক সেকেন্ড অপেক্ষা করুন। তারপর নিচের তথ্যগুলো দেখা যাবে:
   - **P2P Network Name:** যেমন `DIRECT-xx-...`
   - **Wi-Fi Direct Passphrase:** চোখের আইকনে চাপলে দেখা যাবে, কপি আইকনে কপি হবে
   - **SOCKS5 Proxy Address:** যেমন `192.168.49.1:1080`

এই তিনটা তথ্য Client ফোনে লাগবে। শেয়ারিং বন্ধ করতে **Stop Sharing** চাপুন।

### সেটিংস (ঐচ্ছিক)

Settings-এ গিয়ে এগুলো বদলাতে পারেন। শেয়ারিং চলার সময় বদলালে আবার Start করতে হবে।

- **Gaming Mode:** গেম আর ভয়েস চ্যাটের জন্য কম delay। সাধারণত চালু রাখুন।
- **MTU:** 1200 থেকে 1500। ডিফল্ট 1350। সমস্যা না হলে বদলানোর দরকার নেই।
- **SOCKS5 Port:** ডিফল্ট 1080। 1024-65535 এর মধ্যে বদলানো যায়।
- **Authentication:** চালু করলে Client-কে username আর password দিতে হবে।

---

## ৪. Client ফোনকে Host-এর Wi-Fi Direct-এ যুক্ত করা

**পদ্ধতি A (সহজ):**
1. Client ফোনে **Settings → Wi-Fi** খুলুন।
2. Host ফোনে দেখানো নাম (`DIRECT-xx-...`) বেছে নিন।
3. Passphrase দিন, তারপর Connect করুন।

**পদ্ধতি B:** Settings → Wi-Fi → Advanced → Wi-Fi Direct থেকে Host ফোনের নামে চাপুন।

**পদ্ধতি C (QR, সবচেয়ে সহজ):** Host ফোনের GameShare-এ Wi-Fi QR code দেখা যাবে। Client ফোনের ক্যামেরা বা Wi-Fi QR scanner দিয়ে সেটা স্ক্যান করুন। Client ফোনে GameShare install করা লাগবে না। তবে ইন্টারনেটের জন্য ৫ নম্বর ধাপের SOCKS5 app-টা লাগবেই।

যুক্ত হলে Client-এর Wi-Fi-তে "Connected" বা "No internet" দেখাতে পারে। এটা স্বাভাবিক, কারণ ইন্টারনেট আসবে SOCKS5 proxy দিয়ে।

---

## ৫. Client ফোনে SOCKS5 proxy সেট করা

**গুরুত্বপূর্ণ:** Android-এর Wi-Fi-র নিজস্ব proxy (Advanced → Proxy) শুধু HTTP proxy সাপোর্ট করে। এতে UDP আর গেমের সংযোগ যায় না। তাই একটা SOCKS5 সাপোর্ট করা app লাগবে।

একটা open-source app ব্যবহার করুন, যেমন **SocksDroid** বা hev-socks5-tunnel-ভিত্তিক একটা app। Play Store বা GitHub থেকে নিন। নিচের মান বসান:

| সেটিং | মান |
|---|---|
| Proxy Type | SOCKS5 |
| Server / Host | `192.168.49.1` (GameShare-এ যে IP দেখাচ্ছে সেটাই) |
| Port | `1080` (আপনি বদলে থাকলে সেটা) |
| UDP Forwarding | **চালু** (গেম আর ভয়েস চ্যাটের জন্য দরকার) |
| Username / Password | শুধু Authentication চালু থাকলে |

তারপর app-এর **Start/Connect** চাপুন। Client ফোনে VPN-এর মতো একটা চাবি আইকন দেখাবে। Host-এর GameShare-এ status **Active** হবে, আর **Connected Devices** ও **Upload/Download** সংখ্যা প্রতি সেকেন্ডে আপডেট হবে।

শুধু নির্দিষ্ট app proxy-তে চালাতে চাইলে app-এর নিজের proxy setting-এ `socks5://192.168.49.1:1080` দিন (যেমন browser বা গেম যদি SOCKS5 সাপোর্ট করে)।

---

## ৬. সমস্যা হলে যা দেখবেন

| সমস্যা | সম্ভাব্য কারণ ও সমাধান |
|---|---|
| Start Sharing চাপলে error আসে | Wi-Fi চালু আছে কিনা দেখুন। Wi-Fi বন্ধ করে আবার চালু করে চেষ্টা করুন। Location ও Nearby অনুমতি দেওয়া আছে কিনা দেখুন। |
| "Wi-Fi Direct is busy" | Host-এর Wi-Fi বন্ধ-চালু করুন, তারপর আবার Start করুন। |
| "No Mobile Data" দেখায় | Host ফোনে মোবাইল ডাটা চালু করুন। |
| Client Wi-Fi Direct-এ যুক্ত হতে পারছে না | Passphrase ভুল হতে পারে। Host-এ Stop করে আবার Start করুন, নতুন passphrase নিন। |
| Client-এ ইন্টারনেট আসছে না | SOCKS5 app-এ IP ও port ঠিক আছে কিনা দেখুন। Host-এর status **Active** না হলে proxy কাজ করবে না। |
| গেমে সংযোগ হয় কিন্তু voice বা UDP কাজ করে না | Client-এর SOCKS5 app-এ **UDP Forwarding** চালু করুন। |
| Authentication দিয়েও ঢুকতে পারছে না | Host-এর Settings-এ username/password দেখুন, তারপর Client-এ একই মান বসান। |
| ডাটা খুব ধীর | MTU 1350 রাখুন। Gaming Mode চালু আছে কিনা দেখুন। Host-এর কাছাকাছি থাকুন। |
| Host ফোনের ব্যাটারি দ্রুত কমছে | শেয়ারিং চলার সময় এমনিতেই বেশি শক্তি লাগে। শেষ হলে Stop Sharing চাপুন। |

---

## ৭. Huawei Y5 Prime 2018 (Android 8.1) সম্পর্কে

- **Client হিসেবে:** এই ফোন Host-এর Wi-Fi Direct-এ যুক্ত হয়ে SOCKS5 app দিয়ে ইন্টারনেট ব্যবহার করতে পারবে।
- **Host হিসেবে:** GameShare-এর APK এখন এই ফোনে install হয়। তবে ফোনের RAM কম (2 GB) এবং EMUI Wi-Fi Direct-এ সীমাবদ্ধতা রাখতে পারে। দীর্ঘ সময় শেয়ারিং চালালে সমস্যা হতে পারে, তাই পরীক্ষা করে দেখুন।

---

## ৮. আইনি ও নিরাপত্তা নোট

- আপনার মোবাইল অপারেটরের শর্তাবলী দেখে নিন। ডাটা শেয়ার করা সেখানে অনুমোদিত কিনা তা অপারেটরের উপর নির্ভর করে।
- যাদের সাথে ডাটা শেয়ার করছেন, শুধু তাদের সাথেই শেয়ার করুন।
- Authentication চালু রাখলে শক্ত password দিন। না হলে অচেনা কেউ proxy ব্যবহার করতে পারে।
- শেয়ারিং শেষ হলে Stop Sharing চাপুন।

---

## ৯. এক নজরে (Quick Start)

1. **Host:** মোবাইল ডাটা চালু → GameShare → Start Sharing → Network name, passphrase, SOCKS5 address লিখে রাখুন।
2. **Client:** Wi-Fi-তে Host-এর network বেছে নিন → passphrase দিন।
3. **Client:** SOCKS5 app-এ Host IP ও port বসান → UDP Forwarding চালু → Connect।
4. Host-এর status **Active** হলে শেষ।
