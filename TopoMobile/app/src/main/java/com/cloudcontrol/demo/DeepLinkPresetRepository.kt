package com.cloudcontrol.demo

data class DeepLinkPresetItem(
    val appName: String,
    val featureName: String,
    val deeplink: String
)

object DeepLinkPresetRepository {
    private const val RAW_PRESETS = """
系统|SET_ALARM：设置闹钟（8:00）|intent:#Intent;action=android.intent.action.SET_ALARM;i.android.intent.extra.alarm.HOUR=8;i.android.intent.extra.alarm.MINUTES=0;S.android.intent.extra.alarm.MESSAGE=TopoClaw闹钟测试;B.android.intent.extra.alarm.SKIP_UI=true;end
系统|CALL_PHONE：直接拨打 10086|intent://10086#Intent;scheme=tel;action=android.intent.action.CALL;end
系统|CAMERA：打开相机拍照|intent:#Intent;action=android.media.action.IMAGE_CAPTURE;end
系统|编辑短信（10086）|intent://10086#Intent;scheme=smsto;action=android.intent.action.SENDTO;S.sms_body=TopoClaw短信测试;end
系统|编辑日历（日程新建）|intent://com.android.calendar/events#Intent;scheme=content;action=android.intent.action.INSERT;S.title=TopoClaw日历测试;l.beginTime=1776000000000;l.endTime=1776003600000;end
系统|新建联系人|intent:#Intent;action=android.intent.action.INSERT;type=vnd.android.cursor.dir/contact;S.name=TopoClaw测试联系人;S.phone=13800138000;end
支付宝|扫一扫|alipayqr://platformapi/startapp?saId=10000007
支付宝|付款码|alipayqr://platformapi/startapp?saId=20000056
支付宝|收款码|alipays://platformapi/startapp?appId=20000123
支付宝|账单|alipays://platformapi/startapp?appId=20000003
支付宝|余额宝|alipays://platformapi/startapp?appId=20000032
支付宝|花呗|alipays://platformapi/startapp?appId=20000199
支付宝|借呗|alipays://platformapi/startapp?appId=20000180
支付宝|还信用卡|alipays://platformapi/startapp?appId=09999999
支付宝|芝麻信用|alipays://platformapi/startapp?appId=20000118
支付宝|蚂蚁森林|alipays://platformapi/startapp?appId=60000002
支付宝|蚂蚁庄园|alipays://platformapi/startapp?appId=66666674
支付宝|蚂蚁会员|alipays://platformapi/startapp?appId=20000160
支付宝|手机充值|alipays://platformapi/startapp?appId=10000003
支付宝|生活缴费|alipays://platformapi/startapp?appId=20000193
支付宝|快递查询|alipays://platformapi/startapp?appId=20000754
支付宝|火车票/机票|alipays://platformapi/startapp?appId=20000135
支付宝|股票|alipays://platformapi/startapp?appId=20000134
支付宝|记账本|alipays://platformapi/startapp?appId=20000168
支付宝|乘车码|alipayqr://platformapi/startapp?saId=200011235
QQ音乐|打开QQ音乐|qqmusic://
QQ音乐|搜索歌曲|qqmusic://qq.com/ui/search
QQ音乐|听歌识曲|qqmusic://qq.com/ui/recognize
QQ音乐|我喜欢|qqmusic://qq.com/ui/myTab?p=%7B%22tab%22%3A%22fav%22%7D
QQ音乐|继续播放|qqmusic://qq.com/media/resumeSong?p={}
小红书|主页|xhsdiscover://home/
小红书|发现|xhsdiscover://home/explore/
小红书|关注列表|xhsdiscover://home/follow/
小红书|同城|xhsdiscover://home/localfeed/
小红书|商城|xhsdiscover://home/store/
小红书|搜索关键词|xhsdiscover://search/result?keyword=关键词
小红书|搜索笔记|xhsdiscover://search/result?keyword=关键词&target_search=notes
小红书|搜索商品(搜索页)|xhsdiscover://search/result?keyword=关键词&target_search=goods
小红书|搜索用户|xhsdiscover://search/result?keyword=关键词&target_search=users
小红书|我的主页|xhsdiscover://profile/
小红书|编辑资料|xhsdiscover://me/profile/
小红书|消息中心|xhsdiscover://messages/
小红书|收到的赞和收藏|xhsdiscover://message/collections
小红书|收到的评论|xhsdiscover://message/comments
小红书|新增关注|xhsdiscover://message/followers
小红书|发布笔记|xhsdiscover://post_note/
小红书|发布视频|xhsdiscover://post_video/
小红书|话题页|xhsdiscover://topic/v2/{keyword}
小红书|通用设置|xhsdiscover://general_setting/
小红书|通知设置|xhsdiscover://notification_setting/
小红书|账号绑定|xhsdiscover://account/bind/
小红书|深色模式|xhsdiscover://dark_mode_setting/
QQ|打开QQ|mqq://
QQ|加好友/查看名片|mqqapi://card/show_pslcard?src_type=internal&version=1&uin={QQ号}
QQ|打开QQ群|mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&uin={QQ群号}
QQ|与指定QQ聊天|mqqwpa://im/chat?chat_type=wpa&uin={QQ号}
抖音|首页Feed|snssdk1128://feed?refer=web
抖音|搜索|snssdk1128://search?keyword={关键词}
抖音|热搜词榜|snssdk1128://search/trending
微博|打开首页|sinaweibo://gotohome
微博|发现页面|sinaweibo://discover
微博|搜索|sinaweibo://searchall?q={关键词}
微博|扫一扫|sinaweibo://qrcode
京东|打开京东|openApp.jdMobile://
京东|搜索商品|openApp.jdMobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22search%22%2C%22keyword%22%3A%22关键词%22%7D
京东|购物车|openApp.jdMobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22cart%22%7D
京东|领京豆签到|openApp.jdMobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22modulename%22%3A%22JDReactCollectJDBeans%22%2C%22des%22%3A%22jdreactcommon%22%2C%22param%22%3A%7B%22page%22%3A%22collectJDBeansHomePage%22%7D%7D
拼多多|打开拼多多|pinduoduo://
拼多多|搜索商品|pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key={关键词}
拼多多|签到|pinduoduo://com.xunmeng.pinduoduo/https://mobile.yangkeduo.com/pythagoras_ctc_ca.html
拼多多|个人中心|pinduoduo://com.xunmeng.pinduoduo/personal.html
美团|打开美团|imeituan://
美团|美团搜索|imeituan://www.meituan.com/search?q=火锅
美团|扫一扫|imeituan://www.meituan.com/scanQRCode
QQ邮箱|打开QQ邮箱|qqmail://
QQ邮箱|写邮件|intent://{邮箱}?subject={主题}&body={正文}#Intent;scheme=mailto;package=com.tencent.androidqqmail;end
哔哩哔哩|打开首页|bilibili://home
哔哩哔哩|搜索|bilibili://search?keyword={关键词}
哔哩哔哩|扫一扫|bilibili://qrcode
哔哩哔哩|动态|bilibili://following/home
哔哩哔哩|收藏|bilibili://main/favorite
    """

    private val allCases: List<DeepLinkPresetItem> by lazy {
        RAW_PRESETS.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size != 3) return@mapNotNull null
                DeepLinkPresetItem(
                    appName = parts[0].trim(),
                    featureName = parts[1].trim(),
                    deeplink = parts[2].trim()
                )
            }
            .toList()
    }

    fun getAppNames(): List<String> {
        return allCases.map { it.appName }.distinct()
    }

    fun getCasesByApp(appName: String): List<DeepLinkPresetItem> {
        return allCases.filter { it.appName == appName }
    }
}
