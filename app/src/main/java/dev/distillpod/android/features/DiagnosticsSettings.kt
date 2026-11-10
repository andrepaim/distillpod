package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun DiagnosticsSettings(vm:AppViewModel) {
    val diagnostics=vm.graph.diagnostics
    var enabled by remember {mutableStateOf(diagnostics.enabled)}
    var usage by remember {mutableStateOf(diagnostics.usageEnabled)}
    var health by remember {mutableStateOf(diagnostics.reportingHealth)}
    var busy by remember {mutableStateOf(false)}
    val status by diagnostics.status.collectAsState()
    LaunchedEffect(status) { while(true) {health=withContext(Dispatchers.IO){diagnostics.reportingHealth};kotlinx.coroutines.delay(10000)} }
    val clipboard=LocalClipboardManager.current
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Help investigate problems",style=MaterialTheme.typography.headlineSmall)
        Text("Send technical events and error reports to Sentry, including app version, operation stages and network state. Audio, transcripts, questions, answers and credentials are excluded.")
        LabeledSettingSwitch("Share diagnostics",checked=enabled,enabled=!busy && diagnostics.configured,onCheckedChange={allow ->
                busy=true;scope.launch { withContext(Dispatchers.IO){diagnostics.consent(allow)};enabled=diagnostics.enabled;busy=false }
            })
        LabeledSettingSwitch("Share usage statistics",checked=usage,enabled=!busy && diagnostics.configured,onCheckedChange={allow ->
                busy=true;scope.launch {withContext(Dispatchers.IO){diagnostics.usageConsent(allow)};usage=diagnostics.usageEnabled;busy=false}
            })
        Text("Optional counts of feature use and phone, headphone or car triggers. Independent of diagnostics and library sync. No podcast content is sent.",style=MaterialTheme.typography.bodySmall)
        Text(health,style=MaterialTheme.typography.bodySmall)
        Text(if(diagnostics.configured)status else "Cloud reporting is not configured in this build.")
        Text("Reports upload when internet is available, including on mobile data. Pending reports expire after 72 hours. Turning this off clears pending reports; previously uploaded reports follow Sentry retention.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=enabled && diagnostics.configured,onClick={scope.launch(Dispatchers.IO){diagnostics.test()}}){Text("Send test diagnostic")}
        OutlinedButton(onClick={clipboard.setText(AnnotatedString("DistillPod diagnostic reference: ${diagnostics.reference}\nApproximate time: ${java.time.OffsetDateTime.now()}\nDescribe the action, expected result and what happened. No podcast content or credentials are needed."))}){Text("Copy diagnostic reference")}
        Text("When reporting a problem, include the reference, approximate time and the action you tried. A missing headphone event cannot prove whether Android delivered the press.",style=MaterialTheme.typography.bodySmall)
    }
}
