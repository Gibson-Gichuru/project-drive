package org.projectdrive.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import org.projectdrive.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Charcoal map controls stay dark over both the day and night map canvases.
private val Chrome = darkColorScheme(
    primary=Color(0xFFF6F7F8), onPrimary=Color(0xFF202224),
    primaryContainer=Color(0xFF414548), onPrimaryContainer=Color(0xFFF6F7F8),
    secondary=Color(0xFF9FE5BA), onSecondary=Color(0xFF182C20),
    secondaryContainer=Color(0xFF304B3C), onSecondaryContainer=Color(0xFFC5F5D5),
    tertiary=Color(0xFFA8C7FA), tertiaryContainer=Color(0xFF30425D), onTertiaryContainer=Color(0xFFD3E3FF),
    background=Color(0xFF1B1D1F), onBackground=Color(0xFFF6F7F8),
    surface=Color(0xFF242628), onSurface=Color(0xFFF6F7F8),
    surfaceVariant=Color(0xFF44484B), onSurfaceVariant=Color(0xFFBEC4C8),
    outline=Color(0xFF8D959B), outlineVariant=Color(0xFF454A4E),
    error=Color(0xFFFFB4AB), onError=Color(0xFF690005),
    errorContainer=Color(0xFF63302C), onErrorContainer=Color(0xFFFFDAD6),
)
private val Poppins=FontFamily(
    Font(R.font.poppins_regular,FontWeight.Normal),
    Font(R.font.poppins_medium,FontWeight.Medium),
    Font(R.font.poppins_semibold,FontWeight.SemiBold),
    Font(R.font.poppins_bold,FontWeight.Bold),
)
private val DriveTypography=Typography(
    displayLarge=TextStyle(fontFamily=Poppins,fontSize=52.sp,lineHeight=58.sp),
    displayMedium=TextStyle(fontFamily=Poppins,fontSize=42.sp,lineHeight=48.sp),
    displaySmall=TextStyle(fontFamily=Poppins,fontSize=34.sp,lineHeight=40.sp),
    headlineSmall=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=22.sp,lineHeight=28.sp),
    titleSmall=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=20.sp),
    headlineLarge=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=30.sp,lineHeight=36.sp,letterSpacing=(-0.6).sp),
    headlineMedium=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=26.sp,lineHeight=32.sp,letterSpacing=(-0.4).sp),
    titleLarge=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=22.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontFamily=Poppins,fontSize=16.sp,lineHeight=23.sp),
    bodyMedium=TextStyle(fontFamily=Poppins,fontSize=14.sp,lineHeight=21.sp),
    bodySmall=TextStyle(fontFamily=Poppins,fontSize=12.sp,lineHeight=18.sp),
    labelLarge=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=20.sp),
    labelMedium=TextStyle(fontFamily=Poppins,fontWeight=FontWeight.SemiBold,fontSize=12.sp,lineHeight=16.sp,letterSpacing=0.6.sp),
    labelSmall=TextStyle(fontFamily=Poppins,fontSize=11.sp,lineHeight=16.sp),
)
@Composable
fun DriveTheme(content: @Composable ()->Unit) {
    val colors=Chrome
    MaterialTheme(colorScheme=colors,typography=DriveTypography,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(30.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground,content=content)
    }
}
