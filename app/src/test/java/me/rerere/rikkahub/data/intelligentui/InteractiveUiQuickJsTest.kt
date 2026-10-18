package me.rerere.rikkahub.data.intelligentui

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InteractiveUiQuickJsTest {
    @Test fun `real QuickJS renders bindings and formatting under bounded memory and time`() = runBlocking {
        val engine=listOf(File("src/main/assets/intelligent-ui/engine.js"),File("app/src/main/assets/intelligent-ui/engine.js")).first{it.isFile}.readText()
        val js=QuickJs.create(Dispatchers.Default)
        try {
            js.memoryLimit=32L*1024*1024;js.maxStackSize=512L*1024;js.evaluationTimeoutMillis=2000
            val begin=System.nanoTime()
            js.evaluate<Any?>(engine)
            val initMs=(System.nanoTime()-begin)/1_000_000
            js.evaluationTimeoutMillis=600
            val source="\$n = 2\nroot = Col([Slider(\$n,0,10,1,\"N\"),Text(@Format(\$n*123.45,\"currency\",2,\"CNY\")),Button(\"Plus\",@Set(\$n,\$n+1))])"
            suspend fun render(state:JsonObject=JsonObject(emptyMap()),event:JsonObject?=null):JsonObject {
                val request=buildJsonObject{put("source",source);put("state",state);event?.let{put("event",it)}}
                val result=Json.parseToJsonElement(js.evaluate<String>("RikkaOpenUI.renderJson(${JsonPrimitive(request.toString())})")).jsonObject
                assertFalse(result.toString(),result.containsKey("error"));return result
            }
            val start=System.nanoTime()
            var result=render()
            assertEquals(2,result["state"]!!.jsonObject["\$n"]!!.jsonPrimitive.int)
            result=render(result["state"]!!.jsonObject,buildJsonObject{put("type","change");put("target","root/children/0/value");put("value",4)})
            assertEquals(4,result["state"]!!.jsonObject["\$n"]!!.jsonPrimitive.int)
            assertEquals("¥493.80",result["root"]!!.jsonObject["props"]!!.jsonObject["children"]!!.jsonArray[1].jsonObject["props"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            repeat(20){result=render(result["state"]!!.jsonObject)}
            println("OPENUI_QUICKJS initMs=$initMs render22Ms=${(System.nanoTime()-start)/1_000_000} memory=${js.memoryUsage}")
            js.evaluationTimeoutMillis=20
            var timedOut=false
            try {js.evaluate<Any?>("while(true){}") } catch (_:Exception){timedOut=true}
            assertTrue("QuickJS interrupt handler must stop runaway evaluation",timedOut)
        } finally {js.close()}
    }
}
