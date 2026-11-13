package org.projectdrive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionHeading(eyebrow:String,title:String,symbol:DriveSymbol,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        DriveIcon(symbol,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.secondary)
        Text(eyebrow,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
    }
    Text(title,style=MaterialTheme.typography.headlineMedium)
    }
}
@Composable
fun JourneyCard(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.surface,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(22.dp),color=color,tonalElevation=1.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable
fun JourneyMetric(value:String,label:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
        Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun StatusNote(text:String,modifier:Modifier=Modifier,active:Boolean=false) {
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=if(active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
        Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape))
            Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface)
        }
    }
}
