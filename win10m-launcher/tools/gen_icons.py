#!/usr/bin/env python3
"""Builds the launcher icon pack into app/src/main/assets/icons.

  brands.tsv  slug <TAB> brand hex <TAB> SVG path (24x24, filled)   -- from Simple Icons (CC0-1.0)
  glyphs.tsv  name <TAB> SVG path (24x24, stroked 1.25)              -- original line glyphs drawn for this project
  map.tsv     android package <TAB> slug | @glyph

Usage: python3 tools/gen_icons.py <path to unpacked simple-icons npm package>
"""
import json, os, re, sys

SI = sys.argv[1]
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "icons")

# ---------------------------------------------------------------- original glyphs (stroke style)
GLYPHS = {
    "phone": "M6 3h3l1.5 4.5-2 1.5c1 2.5 3 4.5 5.5 5.5l1.5-2L20 14v3c0 1.5-1 3-3 3C10 20 4 14 4 6c0-1.5 1-3 2-3z",
    "message": "M3 5h18v11H10l-4 4v-4H3z",
    "people": "M12 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z M5 20c0-4 3-6.5 7-6.5s7 2.5 7 6.5",
    "camera": "M3 7h4l1.5-2.5h7L17 7h4v12H3z M12 9.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z",
    "settings": "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z M12 5.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z M18.5 12h3 M5.5 12h-3 M12 18.5v3 M12 5.5v-3 M16.6 16.6l2.1 2.1 M7.4 7.4L5.3 5.3 M16.6 7.4l2.1-2.1 M7.4 16.6l-2.1 2.1",
    "photos": "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6 M15.5 8a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z",
    "clock": "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5.5l4 2",
    "calculator": "M5 3h14v18H5z M8 6h8v3H8z M8 13h2 M11 13h2 M14 13h2 M8 17h2 M11 17h2 M14 17h2",
    "calendar": "M3 5h18v15H3z M3 9h18 M7 3v4 M17 3v4",
    "browser": "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M3 12h18 M12 3c-5 5-5 13 0 18 M12 3c5 5 5 13 0 18",
    "music": "M9 18V5l11-2v13 M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    "folder": "M2 5h7l2 3h11v12H2z",
    "mail": "M2 5h20v14H2z M2 5l10 8 10-8",
    "map": "M12 2c-4 0-7 3-7 7 0 5 7 13 7 13s7-8 7-13c0-4-3-7-7-7z M12 6.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z",
    "store": "M4 8h16l-1 13H5z M8.5 8V6.5a3.5 3.5 0 0 1 7 0V8",
    "cart": "M2 4h3l3 11h10l2.5-8H6.2 M9 17.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z M17 17.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z",
    "cloud": "M7 18h10a4 4 0 0 0 .5-8 5.5 5.5 0 0 0-10.6 1.2A3.5 3.5 0 0 0 7 18z",
    "weather": "M12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9L7 7 M17 17l2.1 2.1 M19.1 4.9L17 7 M7 17l-2.1 2.1",
    "notes": "M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5",
    "video": "M3 5h18v14H3z M10 9l5 3-5 3z",
    "mic": "M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M6 11a6 6 0 0 0 12 0 M12 17v4 M8.5 21h7",
    "download": "M12 3v12 M7 10.5l5 5 5-5 M4 20h16",
    "compass": "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M15.5 8.5l-2 5-5 2 2-5z",
    "shield": "M12 2.5l8 3v6c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10v-6z M8.5 12l2.5 2.5 4.5-5",
    "brush": "M4 20c0-3 1.5-4.5 3.5-4.5S11 17 11 18.5 9 20 4 20z M9.5 15.5L19 4l1.5 1.5L11.5 17",
    "wallet": "M3 6h16v3h2v10H3z M3 9h16 M16 13.5h2",
    "game": "M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.5H9A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4z M6.5 10.5v3 M5 12h3 M15.5 11h1 M17.5 13h1",
    "news": "M3 4h14v16H3z M17 8h4v10a2 2 0 0 1-4 0 M6 8h8 M6 12h8 M6 16h5",
    "car": "M4 16v-5l2-5h12l2 5v5z M4 11h16 M6.5 16v2.5 M17.5 16v2.5 M7 13.5h1.5 M15.5 13.5H17",
    "food": "M7 3v7a2 2 0 0 0 4 0V3 M9 3v18 M16 3c-2 2-2.5 5-2.5 9H16z M16 12v9",
    "train": "M6 3h12v13H6z M6 10h12 M9 13h.5 M14.5 13h.5 M8 16l-2.5 5 M16 16l2.5 5 M6.5 19h11",
    "plane": "M12 2c1 0 1.5 1 1.5 2.5V9l8 5v2l-8-2.5V18l2.5 2v1.5l-4-1-4 1V20l2.5-2v-4.5l-8 2.5v-2l8-5V4.5C10.5 3 11 2 12 2z",
    "chat": "M3 4h13v9H9l-3 3v-3H3z M18 9h3v9h-2v3l-3-3h-5v-2",
    "book": "M12 6c-2-1.5-5-2-9-2v14c4 0 7 .5 9 2 2-1.5 5-2 9-2V4c-4 0-7 .5-9 2z M12 6v14",
    "search": "M10.5 3a7.5 7.5 0 1 0 0 15a7.5 7.5 0 1 0 0-15z M16 16l5.5 5.5",
    "health": "M12 20.5C5 15 3 12 3 8.5a4.5 4.5 0 0 1 9-2 4.5 4.5 0 0 1 9 2c0 3.5-2 6.5-9 12z",
    "bank": "M3 9l9-6 9 6z M5 9v9 M9.5 9v9 M14.5 9v9 M19 9v9 M3 20h18",
    "package": "M3 7l9-4 9 4v10l-9 4-9-4z M3 7l9 4 9-4 M12 11v10",
    "tv": "M3 6h18v12H3z M8 21h8",
    "wifi": "M2 9a15 15 0 0 1 20 0 M5.5 12.5a10 10 0 0 1 13 0 M9 16a5 5 0 0 1 6 0 M11.6 19.5h.8",
    "bluetooth": "M7 7l10 10-5 5V2l5 5L7 17",
    "flashlight": "M8 2h8v4l-2 3v13h-4V9L8 6z M8 6h8",
    "rotate": "M8 3h8v18H8z M4 9a9 9 0 0 0 0 6 M20 9a9 9 0 0 1 0 6",
    "send": "M3 4l18 8-18 8 3-8z M6 12h9",
    "attach": "M17 7l-8.500 8.5a2.500 2.500 0 0 0 3.5 3.5L20 11a4.500 4.500 0 0 0-6.400-6.400L5 13",
    "star": "M12 3l2.700 5.800 6.300.800-4.600 4.400 1.200 6.300L12 17.200 6.400 20.300l1.200-6.300L3 9.600l6.300-.800z",
    "history": "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5.500l4 2",
    "add": "M12 4v16 M4 12h16",
}

# ---------------------------------------------------------------- package -> icon
BRANDS = """
com.tencent.mm wechat
com.tencent.mobileqq qq
com.tencent.tim qq
com.tencent.qqlite qq
com.qzone qzone
com.eg.android.AlipayGphone alipay
com.taobao.taobao taobao
com.ss.android.ugc.aweme tiktok
com.ss.android.ugc.aweme.lite tiktok
com.zhiliaoapp.musically tiktok
com.ss.android.ugc.trill tiktok
com.sina.weibo sinaweibo
com.weico.international sinaweibo
tv.danmaku.bili bilibili
com.bilibili.app.in bilibili
com.xingin.xhs xiaohongshu
com.zhihu.android zhihu
com.douban.frodo douban
com.baidu.searchbox baidu
com.netease.cloudmusic neteasecloudmusic
com.sankuai.meituan meituan
com.smile.gifmaker kuaishou
com.kuaishou.nebula kuaishou
com.tencent.weread weread
com.deepseek.chat deepseek
ctrip.android.view tripdotcom
com.csair.mbp chinasouthernairlines
com.miHoYo.Yuanshen mihoyo
com.miHoYo.hkrpg mihoyo
com.mihoyo.hyperion mihoyo
com.xiaomi.shop xiaomi
com.mi.global.shop xiaomi
com.xiaomi.smarthome xiaomi
com.vmall.client huawei
com.alibaba.aliyun alibabacloud
com.alibaba.intl.android.apps.poseidon alibabadotcom
com.alibaba.aliexpresshd aliexpress
com.whatsapp whatsapp
com.whatsapp.w4b whatsapp
com.facebook.katana facebook
com.facebook.lite facebook
com.facebook.orca messenger
com.instagram.android instagram
com.instagram.barcelona threads
com.twitter.android x
org.telegram.messenger telegram
org.telegram.messenger.web telegram
org.thunderdog.challegram telegram
com.google.android.youtube youtube
com.google.android.apps.youtube.music youtubemusic
com.netflix.mediaclient netflix
com.spotify.music spotify
com.google.android.gm gmail
com.google.android.apps.maps googlemaps
com.android.chrome googlechrome
com.google.android.apps.docs googledrive
com.google.android.apps.photos googlephotos
com.android.vending googleplay
com.google.android.googlequicksearchbox google
com.google.android.calendar googlecalendar
com.google.android.apps.tachyon googlemeet
com.google.android.apps.translate googletranslate
com.google.android.keep googlekeep
com.google.android.apps.messaging googlemessages
com.google.android.apps.docs.editors.sheets googlesheets
com.google.android.apps.docs.editors.docs googledocs
com.google.android.apps.walletnfcrel googlepay
com.google.ar.lens googlelens
com.google.android.apps.bard googlegemini
com.google.earth googleearth
com.google.android.apps.chromecast.app googlehome
com.google.android.apps.magazines googlenews
com.google.android.videos googletv
com.google.android.apps.authenticator2 googleauthenticator
com.discord discord
com.reddit.frontpage reddit
com.snapchat.android snapchat
com.pinterest pinterest
us.zoom.videomeetings zoom
com.paypal.android.p2pmobile paypal
com.ubercab uber
com.ubercab.eats ubereats
com.airbnb.android airbnb
tv.twitch.android.app twitch
com.valvesoftware.android.steam.community steam
com.github.android github
org.mozilla.firefox firefoxbrowser
org.mozilla.fenix firefoxbrowser
com.opera.browser opera
com.brave.browser brave
com.duckduckgo.mobile.android duckduckgo
org.thoughtcrime.securesms signal
jp.naver.line.android line
com.viber.voip viber
com.kakao.talk kakaotalk
com.tumblr tumblr
com.quora.android quora
com.medium.reader medium
com.duolingo duolingo
com.shazam.android shazam
com.soundcloud.android soundcloud
com.apple.android.music applemusic
com.aspiro.tidal tidal
deezer.android.app deezer
com.audible.application audible
com.dropbox.android dropbox
notion.id notion
com.evernote evernote
com.trello trello
md.obsidian obsidian
com.anthropic.claude claude
ai.perplexity.app.android perplexity
ch.protonmail.android protonmail
org.videolan.vlc vlcmediaplayer
com.plexapp.android plex
org.xbmc.kodi kodi
com.imdb.mobile imdb
com.wbd.stream hbo
com.crunchyroll.crunchyroid crunchyroll
com.tripadvisor.tripadvisor tripadvisor
com.expedia.bookings expedia
com.lyft.android lyft
com.grabtaxi.passenger grab
com.dd.doordash doordash
com.mcdonalds.app mcdonalds
com.mcdonalds.gma.cn mcdonalds
com.starbucks.mobilecard starbucks
com.starbucks.cn starbucks
com.ebay.mobile ebay
com.shopee.sg shopee
com.shopee.tw shopee
com.shopee.id shopee
com.shopee.my shopee
com.shopee.ph shopee
com.shopee.th shopee
com.shopee.vn shopee
com.contextlogic.wish wish
com.etsy.android etsy
com.ingka.ikea.app ikea
com.nike.omega nike
com.adidas.app adidas
com.samsung.android.spay samsungpay
com.scee.psxandroid playstation
com.roblox.client roblox
com.tencent.ig pubg
com.tinder tinder
com.strava strava
com.fitbit.FitbitMobile fitbit
com.garmin.android.apps.connectmobile garmin
org.wikipedia wikipedia
com.figma.mirror figma
org.zotero.android zotero
org.fdroid.fdroid fdroid
com.x8bit.bitwarden bitwarden
com.agilebits.onepassword 1password
com.onepassword.android 1password
com.lastpass.lpandroid lastpass
com.nordvpn.android nordvpn
com.expressvpn.vpn expressvpn
com.wireguard.android wireguard
com.tailscale.ipn tailscale
com.binance.dev binance
com.coinbase.android coinbase
com.revolut.revolut revolut
com.transferwise.android wise
org.joinmastodon.android mastodon
xyz.blueskyweb.app bluesky
com.vkontakte.android vk
com.nhn.android.search naver
com.mercadopago.wallet mercadopago
com.nu.production nubank
net.one97.paytm paytm
com.phonepe.app phonepe
com.application.zomato zomato
in.swiggy.android swiggy
com.gojek.app gojek
"""

# Apps whose logo is not in Simple Icons get one of the project's own category glyphs.
CATEGORY = """
com.jingdong.app.mall cart
com.xunmeng.pinduoduo cart
com.tmall.wireless cart
com.taobao.idlefish cart
com.achievo.vipshop cart
com.taobao.litetao cart
com.amazon.mShop.android.shopping cart
com.zzkko cart
com.einnovation.temu cart
com.qiyi.video video
com.youku.phone video
com.tencent.qqlive video
com.hunantv.imgo.activity video
com.amazon.avod.thirdpartyclient video
com.disney.disneyplus video
tv.acfundanmaku.video video
com.tencent.wemeet.app video
air.tv.douyu.android tv
com.duowan.kiwi tv
com.ss.android.article.news news
com.ss.android.article.lite news
com.tencent.news news
com.netease.newsreader.activity news
com.hupu.games news
com.sdu.didi.psnger car
com.didiglobal.passenger car
com.autonavi.minimap map
com.baidu.BaiduMap map
com.tencent.map map
com.tencent.qqmusic music
com.kugou.android music
cn.kuwo.player music
com.ximalaya.ting.android mic
com.alibaba.android.rimet chat
com.ss.android.lark chat
com.tencent.wework chat
com.slack chat
com.microsoft.teams chat
com.skype.raider chat
com.openai.chatgpt chat
com.larus.nova chat
com.tencent.hunyuan.app.chat chat
com.moonshot.kimichat chat
com.linkedin.android people
com.baidu.netdisk cloud
com.alicloud.databox cloud
com.microsoft.skydrive cloud
com.quark.browser browser
com.UCMobile browser
com.microsoft.emmx browser
com.tencent.mtt browser
me.ele food
com.dianping.v1 food
com.sankuai.meituan.takeoutnew food
com.lucky.luckyclient food
com.yek.android.kfc.activitys food
com.MobileTicket train
com.umetrip.android.msky.app plane
com.taobao.trip plane
com.booking plane
com.unionpay wallet
cmb.pb bank
com.icbc bank
com.chinamworld.main bank
com.android.bankabc bank
com.chinamworld.bocmbci bank
com.cainiao.wireless package
com.sf.activity package
com.gotokeep.keep health
com.max.xiaoheihe game
com.tencent.tmgp.sgame game
com.tencent.tmgp.pubgmhd game
com.mojang.minecraftpe game
com.microsoft.xboxone.smartglass game
com.coolapk.market store
com.xiaomi.market store
com.huawei.appmarket store
com.oppo.market store
com.heytap.market store
com.bbk.appstore store
com.tencent.android.qqdownloader store
cn.wps.moffice_eng notes
com.microsoft.office.officehubrow notes
com.microsoft.office.outlook mail
com.tencent.androidqqmail mail
com.netease.mail mail
com.adobe.reader book
com.amazon.kindle book
com.duokan.reader book
com.qidian.QDReader book
com.xunlei.downloadprovider download
com.tencent.qqpimsecure shield
"""

def pairs(s):
    return [l.split() for l in s.strip().splitlines() if l.strip()]

data = {e["slug"]: e for e in json.load(open(os.path.join(SI, "data", "simple-icons.json")))}
os.makedirs(OUT, exist_ok=True)
used = sorted({slug for _, slug in pairs(BRANDS)})
with open(os.path.join(OUT, "brands.tsv"), "w") as f:
    for slug in used:
        svg = open(os.path.join(SI, "icons", slug + ".svg")).read()
        path = re.search(r'<path d="([^"]+)"', svg).group(1)
        f.write(f"{slug}\t{data[slug]['hex']}\t{path}\n")
with open(os.path.join(OUT, "glyphs.tsv"), "w") as f:
    for name, path in GLYPHS.items():
        f.write(f"{name}\t{path}\n")
with open(os.path.join(OUT, "map.tsv"), "w") as f:
    for pkg, slug in pairs(BRANDS):
        f.write(f"{pkg}\t{slug}\n")
    for pkg, g in pairs(CATEGORY):
        assert g in GLYPHS, g
        f.write(f"{pkg}\t@{g}\n")
print(len(used), "brand icons,", len(GLYPHS), "glyphs,", len(pairs(BRANDS)) + len(pairs(CATEGORY)), "packages")
