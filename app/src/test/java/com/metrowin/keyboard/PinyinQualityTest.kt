package com.metrowin.keyboard

import com.metrowin.keyboard.engine.PinyinDictionary
import com.metrowin.keyboard.engine.PinyinEngine
import com.metrowin.keyboard.engine.PinyinPairs
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How often the first thing the bar offers is what was meant, on everyday sentences typed in full Pinyin without a pause. A change
 * of the engine or of the word list that makes this worse fails here.
 */
class PinyinQualityTest {
    private val engine by lazy {
        PinyinEngine(
            File("src/main/assets/pinyin/zh.txt").bufferedReader().use { PinyinDictionary.parse(it) },
            pairs = File("src/main/assets/pinyin/zh.pairs").bufferedReader().use { PinyinPairs.parse(it) },
        )
    }

    private val cases = listOf(
        "nihao" to "你好", "nihaoma" to "你好吗", "xiexie" to "谢谢", "duibuqi" to "对不起", "meiguanxi" to "没关系",
        "zaijian" to "再见", "wanan" to "晚安", "zaoshanghao" to "早上好", "women" to "我们", "zhongguo" to "中国",
        "jintian" to "今天", "mingtian" to "明天", "xianzai" to "现在", "shenme" to "什么", "zenme" to "怎么",
        "weishenme" to "为什么", "keyi" to "可以", "zhidao" to "知道", "xihuan" to "喜欢", "pengyou" to "朋友",
        "dianhua" to "电话", "shouji" to "手机", "weixin" to "微信", "gongzuo" to "工作", "xuexi" to "学习",
        "wojintianhengaoxing" to "我今天很高兴", "nizaiganshenme" to "你在干什么", "womenyiqiquchifan" to "我们一起去吃饭",
        "mingtianjian" to "明天见", "wozhidaole" to "我知道了", "niqunaliwan" to "你去哪里玩",
        "zhegedongxiduoshaoqian" to "这个东西多少钱", "wobuzhidao" to "我不知道", "tamenzaijia" to "他们在家",
        "jintiantianqihenhao" to "今天天气很好", "wohenxiangni" to "我很想你", "nichifanlema" to "你吃饭了吗",
        "zhongguorenmin" to "中国人民", "woaini" to "我爱你", "xiawuliangdian" to "下午两点",
        "qingwenzenmezou" to "请问怎么走", "haodewozhidaole" to "好的我知道了", "zhezhenshitaihaole" to "这真是太好了",
        "dajiahao" to "大家好", "xinnianhkuaile".replace("hk", "k") to "新年快乐", "shengrikuaile" to "生日快乐",
        "woxianzaizaigongsi" to "我现在在公司", "nibieshengqile" to "你别生气了", "womenzoubaa".dropLast(1) to "我们走吧",
        "zheshiwodeshu" to "这是我的书", "tashiyigehaoren" to "他是一个好人",
    )

    @Test fun theFirstOfferIsWhatWasMeant() {
        var right = 0
        val misses = ArrayList<String>()
        for ((typed, meant) in cases) {
            val first = engine.candidates(typed).firstOrNull()?.text
            if (first == meant) right++ else misses += "$typed → $first (wanted $meant)"
        }
        println("Pinyin: $right / ${cases.size} right first")
        misses.forEach { println("  $it") }
        // Measured when the engine was written: keep at least this.
        assertTrue("$right of ${cases.size}", right >= cases.size * 85 / 100)
    }

    @Test fun anUnfinishedLastSyllableStillReadsAsASentence() {
        val c = engine.candidates("wojintianhengaox").first()
        println("wojintianhengaox → $c")
        assertTrue(c.text, c.text.startsWith("我今天很高"))
    }
}
