package org.projectdrive.app.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small original line icons; no font, image download or icon SDK. */
enum class DriveSymbol { Road, Compass, Convoy, Journal, Locate, Arrow, Edit, Chevron, Shield, Close, Pause }
private fun vector(name:String,draw:PathBuilder.()->Unit)=ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply{
    path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=draw)
}.build()
private val symbols=mapOf(
    DriveSymbol.Road to vector("Road") { moveTo(5f,21f);curveTo(4f,13f,14f,14f,11f,3f);moveTo(15f,21f);curveTo(14f,14f,22f,13f,18f,3f);moveTo(10f,20f);lineTo(10f,18f);moveTo(12f,14f);lineTo(13f,12f);moveTo(15f,8f);lineTo(15f,5f) },
    DriveSymbol.Compass to vector("Compass") { moveTo(12f,2f);curveTo(25.3f,2f,25.3f,22f,12f,22f);curveTo(-1.3f,22f,-1.3f,2f,12f,2f);moveTo(16f,8f);lineTo(13.5f,13.5f);lineTo(8f,16f);lineTo(10.5f,10.5f);close() },
    DriveSymbol.Convoy to vector("Convoy") { moveTo(3f,17f);lineTo(3f,13f);lineTo(5f,9f);lineTo(14f,9f);lineTo(16f,13f);lineTo(16f,17f);close();moveTo(3f,13f);lineTo(16f,13f);moveTo(5f,17f);lineTo(5f,19f);moveTo(14f,17f);lineTo(14f,19f);moveTo(8f,5f);lineTo(18f,5f);lineTo(21f,10f);lineTo(21f,15f);lineTo(19f,15f);moveTo(6f,15f);lineTo(7f,15f);moveTo(12f,15f);lineTo(13f,15f) },
    DriveSymbol.Journal to vector("Journal") { moveTo(6f,3f);lineTo(19f,3f);lineTo(19f,21f);lineTo(6f,21f);curveTo(3f,21f,3f,17f,6f,17f);lineTo(19f,17f);moveTo(5f,3f);lineTo(5f,17f);moveTo(9f,8f);lineTo(15f,8f);moveTo(9f,12f);lineTo(13f,12f) },
    DriveSymbol.Locate to vector("Locate") { moveTo(12f,5f);curveTo(21.3f,5f,21.3f,19f,12f,19f);curveTo(2.7f,19f,2.7f,5f,12f,5f);moveTo(12f,2f);lineTo(12f,7f);moveTo(12f,17f);lineTo(12f,22f);moveTo(2f,12f);lineTo(7f,12f);moveTo(17f,12f);lineTo(22f,12f);moveTo(11f,12f);lineTo(13f,12f) },
    DriveSymbol.Arrow to vector("Arrow") { moveTo(5f,12f);lineTo(19f,12f);moveTo(13f,6f);lineTo(19f,12f);lineTo(13f,18f) },
    DriveSymbol.Edit to vector("Edit") { moveTo(4f,20f);lineTo(5f,14f);lineTo(16f,3f);lineTo(21f,8f);lineTo(10f,19f);close();moveTo(13f,6f);lineTo(18f,11f) },
    DriveSymbol.Chevron to vector("Chevron") { moveTo(6f,9f);lineTo(12f,15f);lineTo(18f,9f) },
    DriveSymbol.Shield to vector("Shield") { moveTo(12f,2f);lineTo(20f,6f);lineTo(20f,12f);curveTo(20f,17f,16f,20f,12f,22f);curveTo(8f,20f,4f,17f,4f,12f);lineTo(4f,6f);close();moveTo(8f,12f);lineTo(11f,15f);lineTo(16f,9f) },
    DriveSymbol.Close to vector("Close") { moveTo(6f,6f);lineTo(18f,18f);moveTo(18f,6f);lineTo(6f,18f) },
    DriveSymbol.Pause to vector("Pause") { moveTo(8f,5f);lineTo(8f,19f);moveTo(16f,5f);lineTo(16f,19f) },
)
@Composable fun DriveIcon(symbol:DriveSymbol,description:String?=null,modifier:Modifier=Modifier,tint:Color=MaterialTheme.colorScheme.onSurface) {
    Icon(checkNotNull(symbols[symbol]),description,modifier,tint)
}
