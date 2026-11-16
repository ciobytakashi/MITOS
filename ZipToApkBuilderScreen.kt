package com.mitos.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ZipToApkBuilderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appName by remember { mutableStateOf("My Web App") }
    var packageId by remember { mutableStateOf("com.example.webapp") }
    var inspection by remember { mutableStateOf<ZipProjectInspection?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var outputToSave by remember { mutableStateOf<File?>(null) }

    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            working = true
            inspection = null
            message = "Inspecting ZIP and preparing project…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        ZipProjectBuilder.inspectAndPrepare(context, uri, appName, packageId)
                    }
                }
                result.onSuccess {
                    inspection = it
                    message = "ZIP inspection finished."
                }.onFailure {
                    message = it.localizedMessage ?: "Could not process this ZIP file."
                }
                working = false
            }
        }
    }

    val saveZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destination ->
        val source = outputToSave
        if (destination != null && source != null) {
            working = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { copyOutput(context, source, destination) }
                }
                message = result.fold(
                    { "Prepared project ZIP saved successfully." },
                    { "Could not save ZIP: ${it.localizedMessage}" }
                )
                working = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Prepare an Android project from a ZIP", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text(
            "MITOS can inspect ZIP files, safely extract their contents, recognize Android Gradle projects, " +
                "and generate a native WebView project from a website ZIP. Compilation still needs Android SDK and Gradle.",
            color = MaterialTheme.colorScheme.secondary
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Web app configuration", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = appName, onValueChange = { appName = it },
                    label = { Text("Application name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = packageId, onValueChange = { packageId = it },
                    label = { Text("Package ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Used when a website ZIP is converted into a WebView project. Android project ZIPs are preserved without changing their package settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }

        Button(
            onClick = { importZip.launch("application/zip") },
            enabled = !working && appName.isNotBlank() && packageId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Select ZIP file") }

        if (working) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text(message ?: "Working…")
            }
        } else {
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        }

        inspection?.let { info ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inspection result", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(info.kind, style = MaterialTheme.typography.headlineSmall)
                    Text("${info.entryCount} ZIP entries inspected")
                    info.mainHtml?.let { Text("Main HTML: $it") }
                    Text("Extracted files are stored in MITOS app storage.", style = MaterialTheme.typography.bodySmall)
                    info.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    info.outputZip?.let { path ->
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                outputToSave = File(path)
                                saveZip.launch(
                                    if (info.kind == "Android project") "MITOS-Android-Project.zip"
                                    else "MITOS-WebView-Project.zip"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save prepared project ZIP") }
                    }
                }
            }
        }

        TextButton(onClick = {
            inspection = null
            message = "Choose another ZIP when you are ready."
        }) { Text("Clear result") }

        Text(
            "Safety: ZIP entries are checked against path traversal and extraction-size limits. " +
                "An Android project is only structurally inspected here; it is not compiled or guaranteed to build.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

private fun copyOutput(context: Context, source: File, destination: Uri) {
    require(source.exists()) { "Prepared ZIP was not found." }
    context.contentResolver.openOutputStream(destination, "w")?.use { output ->
        source.inputStream().use { input -> input.copyTo(output) }
    } ?: error("Android could not open the selected save location.")
}
