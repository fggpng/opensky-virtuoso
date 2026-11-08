package com.opensky.virtuoso.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.database.targets.SmallBodyTarget
import com.opensky.virtuoso.database.targets.Target
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.targets.UserTarget
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.viewmodel.TargetRow
import com.opensky.virtuoso.ui.viewmodel.TargetsViewModel

@Composable
fun TargetsScreen(vm: TargetsViewModel, snackbar: SnackbarHostState) {
    val category by vm.category.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingConfirmation.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)

    var selected by remember { mutableStateOf<TargetRow?>(null) }
    var showUserDialog by remember { mutableStateOf(false) }
    var showJplDialog by remember { mutableStateOf(false) }
    val mpcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importMpc(it) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            label = { Text("Buscar: nombre, HIP 91262, TYC 1-2-1, C/2023 A3…") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TargetCategory.SOLAR_SYSTEM, TargetCategory.NAMED_STAR, TargetCategory.DOUBLE_STAR, TargetCategory.SMALL_BODY, TargetCategory.USER_OBJECT)
                .forEach { cat -> FilterChip(selected = category == cat && query.isBlank(), onClick = { vm.query.value = ""; vm.category.value = cat }, label = { Text(cat.label) }) }
        }
        if (query.isBlank() && category == TargetCategory.SMALL_BODY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.downloadComets() }, enabled = !busy) { Text("Cometas (MPC)") }
                OutlinedButton(onClick = { mpcPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Importar MPC") }
                OutlinedButton(onClick = { showJplDialog = true }, enabled = !busy) { Text("JPL") }
            }
        }
        if (query.isBlank() && category == TargetCategory.USER_OBJECT) {
            Button(onClick = { showUserDialog = true }) { Text("Añadir objeto (AR/Dec J2000)") }
        }
        if (busy) HintText("Trabajando…")
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.target.id }) { row ->
                TargetRowItem(row) { selected = row }
                HorizontalDivider()
            }
        }
    }

    selected?.let { row ->
        TargetDialog(
            row = row,
            onDismiss = { selected = null },
            onGoto = { vm.goto(row.target); selected = null },
            onSync = { vm.sync(row.target); selected = null },
            onDelete = when (val t = row.target) {
                is UserTarget -> ({ vm.deleteUserObject(t.obj.id); selected = null })
                is SmallBodyTarget -> ({ vm.removeSmallBody(t.elements.designation); selected = null })
                else -> null
            },
        )
    }

    pending?.let { (target, reason) ->
        AlertDialog(
            onDismissRequest = { vm.dismissConfirmation() },
            title = { Text("⚠ Advertencia de seguridad") },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = { vm.goto(target, confirmed = true) }) { Text("Entiendo el riesgo, continuar") } },
            dismissButton = { TextButton(onClick = { vm.dismissConfirmation() }) { Text("Cancelar") } },
        )
    }

    if (showUserDialog) UserObjectDialog(onDismiss = { showUserDialog = false }) { n, ra, dec, mag ->
        vm.saveUserObject(n, ra, dec, mag); showUserDialog = false
    }
    if (showJplDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJplDialog = false },
            title = { Text("Buscar en JPL Small-Body DB") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Ceres, 4, 2P, C/2023 A3…") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.lookupJpl(text); showJplDialog = false }) { Text("Buscar y añadir") } },
            dismissButton = { TextButton(onClick = { showJplDialog = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TargetRowItem(row: TargetRow, onClick: () -> Unit) {
    val visible = row.altDeg > 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.target.name, fontWeight = FontWeight.Medium,
                color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            HintText(row.target.subtitle)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Alt %+.0f°".format(row.altDeg), color = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            HintText("Az %.0f°%s".format(row.azDeg, row.magnitude?.let { " · m %.1f".format(it) } ?: ""))
        }
    }
}

@Composable
private fun TargetDialog(row: TargetRow, onDismiss: () -> Unit, onGoto: () -> Unit, onSync: () -> Unit, onDelete: (() -> Unit)?) {
    val t: Target = row.target
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.subtitle)
                Text("Altura %+.1f° · Acimut %.1f°".format(row.altDeg, row.azDeg))
                row.magnitude?.let { Text("Magnitud ≈ %.1f".format(it)) }
                if (row.altDeg <= 0) WarningText("Bajo el horizonte")
                if (t.isSun) WarningText("Solo con filtro solar certificado de apertura completa. Tapa el buscador.")
                HintText("«Sync» indica que el objeto YA está centrado en el ocular: mejora el modelo de apuntado.")
                onDelete?.let { TextButton(onClick = it) { Text("Eliminar de la lista") } }
            }
        },
        confirmButton = { TextButton(onClick = onGoto, enabled = row.altDeg > 0) { Text("GoTo + seguir") } },
        dismissButton = {
            Row {
                TextButton(onClick = onSync) { Text("Sync") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun UserObjectDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ra by remember { mutableStateOf("") }
    var dec by remember { mutableStateOf("") }
    var mag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo objeto de usuario") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(ra, { ra = it }, label = { Text("AR J2000 (h m s) p. ej. 00 42 44.3") }, singleLine = true)
                OutlinedTextField(dec, { dec = it }, label = { Text("Dec J2000 (° ′ ″) p. ej. +41 16 09") }, singleLine = true)
                OutlinedTextField(mag, { mag = it }, label = { Text("Magnitud (opcional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, ra, dec, mag) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
