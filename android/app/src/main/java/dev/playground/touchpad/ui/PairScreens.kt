package dev.playground.touchpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.playground.touchpad.R
import dev.playground.touchpad.net.TouchpadClient.FailReason
import dev.playground.touchpad.net.TouchpadClient.FoundPc
import dev.playground.touchpad.net.TouchpadClient.PairingStep

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) { content() }
}

@Composable
private fun Title(text: String) =
    Text(text, color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Palette.TextMuted, fontSize = 15.sp, lineHeight = 21.sp, modifier = modifier)

/** Lists PCs running touchpad-server on this network. */
@Composable
fun PairScreen(pcs: List<FoundPc>, onPair: (FoundPc) -> Unit) = Page {
    Title(stringResource(R.string.pair_title))
    Spacer(Modifier.height(8.dp))
    Body(stringResource(R.string.pair_intro))
    Spacer(Modifier.height(24.dp))
    if (pcs.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.height(20.dp), color = Palette.TextMuted, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Body(stringResource(R.string.pair_searching))
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(pcs, key = { it.id }) { pc ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pc.name, color = Palette.TextPrimary, fontSize = 17.sp)
                    Text(
                        if (pc.pairingOpen) pc.address else stringResource(R.string.pair_closed_hint),
                        color = Palette.TextMuted,
                        fontSize = 13.sp,
                    )
                }
                Button(
                    onClick = { onPair(pc) },
                    enabled = pc.pairingOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
                ) { Text(stringResource(R.string.pair_button)) }
            }
        }
    }
}

/** The steps after tapping Pair, including the code comparison. */
@Composable
fun PairingScreen(pcName: String, step: PairingStep, onConfirm: () -> Unit, onCancel: () -> Unit) = Page {
    when (step) {
        PairingStep.Contacting -> {
            Title(stringResource(R.string.pairing_contacting, pcName))
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Palette.TextMuted)
            Spacer(Modifier.weight(1f))
            CancelButton(onCancel)
        }
        is PairingStep.Compare -> {
            Title(stringResource(R.string.pairing_compare_title))
            Spacer(Modifier.height(8.dp))
            Body(stringResource(R.string.pairing_compare_body, pcName))
            Spacer(Modifier.weight(1f))
            Text(
                text = "%03d %03d".format(step.code / 1000, step.code % 1000),
                color = Palette.TextPrimary,
                fontSize = 52.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
            Body(stringResource(R.string.pairing_compare_warning))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            ) { Text(stringResource(R.string.pairing_codes_match)) }
            Spacer(Modifier.height(8.dp))
            CancelButton(onCancel)
        }
        PairingStep.WaitingForPc -> {
            Title(stringResource(R.string.pairing_waiting_title))
            Spacer(Modifier.height(8.dp))
            Body(stringResource(R.string.pairing_waiting_body, pcName))
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Palette.TextMuted)
            Spacer(Modifier.weight(1f))
            CancelButton(onCancel)
        }
        is PairingStep.Failed -> {
            Title(stringResource(R.string.pairing_failed_title))
            Spacer(Modifier.height(8.dp))
            Body(
                stringResource(
                    when (step.reason) {
                        FailReason.CLOSED -> R.string.pairing_failed_closed
                        FailReason.BUSY -> R.string.pairing_failed_busy
                        FailReason.REJECTED -> R.string.pairing_failed_rejected
                        FailReason.TIMED_OUT -> R.string.pairing_failed_timeout
                        FailReason.TAMPERED -> R.string.pairing_failed_tampered
                    },
                ),
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.pairing_back), color = Palette.TextPrimary)
            }
        }
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) =
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.pairing_cancel), color = Palette.TextPrimary)
    }
