package com.metrowin.keyboard

import com.metrowin.keyboard.translate.Provider
import com.metrowin.keyboard.translate.Providers
import com.metrowin.keyboard.translate.TranslateLanguages
import com.metrowin.keyboard.translate.TranslationException
import org.junit.Assert.*
import org.junit.Test

class TranslateTest {
    @Test fun googleWebRequestAndAnswer() {
        val r = Providers.request(Provider.GOOGLE, "ciao mondo", "auto", "en", "", "")
        assertEquals("GET", r.method)
        assertTrue(r.url.startsWith("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=ciao+mondo"))
        // The shape the service really answers with: pieces of text, then the detected language.
        val t = Providers.parse(Provider.GOOGLE, """[[["Hello ","ciao ",null,null,10],["world","mondo",null,null,10]],null,"it"]""")
        assertEquals("Hello world", t.text)
        assertEquals("it", t.detectedLanguage)
    }

    @Test fun googleCloudUsesTheKeyAndDecodesEntities() {
        val r = Providers.request(Provider.GOOGLE_CLOUD, "ciao", "it", "en", "K E", "")
        assertEquals("POST", r.method)
        assertTrue(r.url.endsWith("?key=K+E"))
        assertEquals("q=ciao&target=en&format=text&source=it", String(r.body!!))
        val t = Providers.parse(Provider.GOOGLE_CLOUD, """{"data":{"translations":[{"translatedText":"It&#39;s &quot;ok&quot;","detectedSourceLanguage":"it"}]}}""")
        assertEquals("It's \"ok\"", t.text)
    }

    @Test fun deeplPicksTheRightHostAndRegion() {
        val free = Providers.request(Provider.DEEPL, "ciao", "auto", "en", "abc:fx", "")
        assertTrue(free.url.startsWith("https://api-free.deepl.com/"))
        assertEquals("DeepL-Auth-Key abc:fx", free.headers["Authorization"])
        assertTrue(String(free.body!!).contains("target_lang=EN-US"))
        assertFalse(String(free.body!!).contains("source_lang"))
        assertTrue(Providers.request(Provider.DEEPL, "x", "it", "pt", "abc", "").url.startsWith("https://api.deepl.com/"))
        assertEquals("PT-PT", Providers.deeplTarget("pt"))
        assertEquals("IT", Providers.deeplTarget("it"))
        val t = Providers.parse(Provider.DEEPL, """{"translations":[{"detected_source_language":"IT","text":"Hello"}]}""")
        assertEquals("Hello", t.text); assertEquals("it", t.detectedLanguage)
    }

    @Test fun libreTranslateUsesYourServer() {
        val r = Providers.request(Provider.LIBRE, "ciao", "auto", "en", "", "https://lt.example.org/")
        assertEquals("https://lt.example.org/translate", r.url)
        assertTrue(String(r.body!!).contains("\"target\":\"en\""))
        assertFalse(String(r.body!!).contains("api_key"))
        assertEquals("Hello", Providers.parse(Provider.LIBRE, """{"translatedText":"Hello","detectedLanguage":{"language":"it","confidence":90}}""").text)
    }

    @Test fun myMemoryReportsItsErrors() {
        assertEquals("Hello", Providers.parse(Provider.MYMEMORY, """{"responseData":{"translatedText":"Hello"},"responseStatus":200}""").text)
        assertThrows(TranslationException::class.java) { Providers.parse(Provider.MYMEMORY, """{"responseData":{"translatedText":"x"},"responseStatus":429,"responseDetails":"limit"}""") }
    }

    @Test fun garbageAnswersAreAnErrorNotACrash() {
        for (p in Provider.entries) assertThrows(TranslationException::class.java) { Providers.parse(p, "<html>nope</html>") }
    }

    @Test fun longTextIsCutAtSentenceEnds() {
        val text = "Prima frase qui. Seconda frase qui. Terza frase qui. Quarta frase qui."
        val pieces = Providers.chunk(text, 40)
        assertTrue(pieces.size > 1)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 40 })
        assertEquals(listOf("breve"), Providers.chunk("breve", 40))
    }

    @Test fun providersFallBackToGoogle() {
        assertEquals(Provider.GOOGLE, Provider.from(null))
        assertEquals(Provider.DEEPL, Provider.from("deepl"))
        assertEquals("Auto", TranslateLanguages.name("auto"))
        assertEquals("Italiano", TranslateLanguages.name("it", java.util.Locale.ITALIAN))
    }
}
