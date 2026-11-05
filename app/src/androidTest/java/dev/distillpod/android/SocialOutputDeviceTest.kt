package dev.distillpod.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Opt-in live production pipeline using public sample text. Does not read or mutate the listening library. */
class SocialOutputDeviceTest {
    @Test fun publicSampleThroughBothFormats()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLiveAccount")=="true")
        withContext(Dispatchers.IO) {
            val graph=InstrumentationRegistry.getInstrumentation().targetContext.appGraph;graph.ready.await()
            val sample=InstrumentationRegistry.getArguments().getString("sample") ?: "learning"
            val directory=File(graph.context.getExternalFilesDir(null),"social-output-check"+if(sample=="learning") "" else "-$sample").apply {mkdirs()}
            if (InstrumentationRegistry.getArguments().getString("renderRecordedLive") == "true") {
                val research=JSONObject(File(directory,"research.json").readText())
                val sources=dev.distillpod.android.ai.EvidenceSource.list(research.getJSONArray("sources").toString())
                val brief=InfographicBrief.parse(File(directory,"brief.json").readText(),sources)
                val image=InfographicRenderer.render(brief,File(directory,"illustration.json").readText(),sources,"Public validation sample · Learning through retrieval")
                PostAssets.validate(image); File(directory,"infographic.png").writeBytes(image)
                val report=JSONObject(File(directory,"report.json").readText()).put("infographic",true).put("dimensions","1080x1350").put("renderedFromRecordedLiveInputs",true)
                File(directory,"report.json").writeText(report.toString(2))
                return@withContext
            }
            val composer=SocialComposer(graph.ai,dev.distillpod.android.ai.AccountSocialTools(graph.account.session,graph.settings,inspectResearch={response -> File(directory,"public-research-response.json").writeText(response.toString(2))}))
            val learning=PostContent(show="Public validation sample",episode="Learning through retrieval",excerpt="Rereading can make an idea feel familiar. Trying to recall it without looking shows what you can actually retrieve. Spacing that practice over time can make learning more durable, although the best approach depends on what you need to learn.",quote="Trying to recall it without looking shows what you can actually retrieve.")
            val source=when(sample) {
                "attention" -> PostContent(show="Amostra pública de validação",episode="Atenção no trabalho",excerpt="Quando mudamos de tarefa o tempo todo, precisamos reconstruir o contexto de cada atividade. Agrupar tarefas semelhantes pode reduzir essas transições. Mas uma interrupção também pode ser necessária: a prioridade deve considerar o trabalho e as outras pessoas, não apenas proteger o foco.")
                "feedback" -> PostContent(show="Public validation sample",episode="Learning from feedback",excerpt="Feedback is useful when it helps you see the gap between what you tried and what happened. A specific observation gives you something to change on the next attempt. But more feedback is not always better: timing, the task, and how you interpret it can change whether it helps.")
                else -> learning
            }
            val report=JSONObject().put("ownerHistorySent",false).put("illustrationMethod","AI-designed vector scenes, rendered locally")
            if (!File(directory,"discussion.txt").exists()) {
                val discussion=composer.discussion(SocialResult(source,"discussion"))
                File(directory,"discussion.txt").writeText(SocialResult(source,"discussion",artifact=discussion).shareText())
            }
            report.put("discussion",true);File(directory,"report.json").writeText(report.toString(2))
            val base=SocialResult(source,"infographic")
            val recorded=File(directory,"public-research-response.json")
            val research=if(recorded.exists()) dev.distillpod.android.ai.ToolResponse.evidence(JSONObject(recorded.readText())) else composer.research(base)
            val researched=base.copy(research=research.text,researchSources=JSONArray(research.sources.map {it.json()}).toString())
            File(directory,"research.json").writeText(JSONObject().put("memo",research.text).put("sources",JSONArray(researched.researchSources)).toString(2))
            report.put("sourceCount",research.sources.size);File(directory,"report.json").writeText(report.toString(2))
            val (raw,brief)=composer.brief(researched)
            File(directory,"brief.json").writeText(raw)
            val vector=composer.illustrate(brief);File(directory,"illustration.json").writeText(vector)
            val image=InfographicRenderer.render(brief,vector,research.sources,source.attribution())
            PostAssets.validate(image);File(directory,"infographic.png").writeBytes(image)
            report.put("infographic",true).put("dimensions","1080x1350")
            File(directory,"report.json").writeText(report.toString(2))
        }
    }
}
