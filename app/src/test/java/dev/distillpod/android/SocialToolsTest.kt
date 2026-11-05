package dev.distillpod.android

import dev.distillpod.android.ai.ToolResponse
import okio.Buffer
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SocialToolsTest {
    @Test fun `subscription stream reconstructs output omitted from completion event`() {
        val output=JSONArray().put(JSONObject().put("type","web_search_call").put("status","completed"))
            .put(JSONObject().put("type","message").put("content",JSONArray().put(JSONObject().put("type","output_text").put("text","Evidence from two primary sources.")
                .put("annotations",JSONArray().put(JSONObject().put("type","url_citation").put("title","One").put("url","https://example.org/one"))
                    .put(JSONObject().put("type","url_citation").put("title","Two").put("url","https://example.org/two"))))))
        val buffer=Buffer()
        for(i in 0 until output.length())buffer.writeUtf8("data: "+JSONObject().put("type","response.output_item.done").put("output_index",i).put("item",output.getJSONObject(i))+"\n\n")
        buffer.writeUtf8("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
        val evidence=ToolResponse.evidence(ToolResponse.read(buffer))
        assertEquals(2,evidence.sources.size);assertTrue(evidence.text.contains("Evidence"))
    }
    @Test fun `streamed citations survive missing annotations in final message`() {
        val buffer=Buffer()
        val message=JSONObject().put("type","message").put("content",JSONArray().put(JSONObject().put("type","output_text").put("text","A cited claim.").put("annotations",JSONArray())))
        for(i in 0..1) buffer.writeUtf8("data: "+JSONObject().put("type","response.output_text.annotation.added").put("output_index",1).put("content_index",0).put("annotation_index",i).put("annotation",JSONObject().put("type","url_citation").put("title","Source").put("url","https://example.org/$i"))+"\n\n")
        buffer.writeUtf8("data: "+JSONObject().put("type","response.output_item.done").put("output_index",0).put("item",JSONObject().put("type","web_search_call").put("status","completed"))+"\n\n")
        buffer.writeUtf8("data: "+JSONObject().put("type","response.output_item.done").put("output_index",1).put("item",message)+"\n\n")
        buffer.writeUtf8("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")
        assertEquals(2,ToolResponse.evidence(ToolResponse.read(buffer)).sources.size)
    }
    @Test fun `markdown citations require independent search provenance`() {
        val response=JSONObject().put("output",JSONArray()
            .put(JSONObject().put("type","web_search_call").put("status","completed").put("action",JSONObject().put("type","search").put("sources",JSONArray()
                .put(JSONObject().put("url","https://example.org/a")).put(JSONObject().put("url","https://example.org/b")))))
            .put(JSONObject().put("type","message").put("content",JSONArray().put(JSONObject().put("type","output_text").put("annotations",JSONArray())
                .put("text","Claim [1](https://example.org/a). Another [2](https://example.org/b). Invented [3](https://made-up.example/claim).")))))
        val evidence=ToolResponse.evidence(response)
        assertEquals(listOf("https://example.org/a","https://example.org/b"),evidence.sources.map {it.url})
        response.getJSONArray("output").getJSONObject(0).getJSONObject("action").put("sources",JSONArray())
        try {ToolResponse.evidence(response);fail("unverified links accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun `partial or failed stream never masquerades as a result`() {
        for(text in listOf("data: {\"type\":\"response.in_progress\"}\n\n","data: {\"type\":\"response.failed\"}\n\n")) {
            try {ToolResponse.read(Buffer().writeUtf8(text));fail()}catch(_:IllegalStateException){}
        }
    }
    @Test fun `model prose without an actual search and citations is rejected`() {
        try {ToolResponse.evidence(JSONObject("""{"output":[]}"""));fail()}catch(_:IllegalArgumentException){}
    }
}
