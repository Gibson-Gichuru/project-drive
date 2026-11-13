package org.projectdrive.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
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

private val Day = lightColorScheme(
    primary=Color(0xFF205744), onPrimary=Color.White,
    primaryContainer=Color(0xFFDDEBDD), onPrimaryContainer=Color(0xFF163C2F),
    secondary=Color(0xFF8A492D), onSecondary=Color.White,
    secondaryContainer=Color(0xFFF7DFC5), onSecondaryContainer=Color(0xFF60341F),
    tertiary=Color(0xFF426A73), tertiaryContainer=Color(0xFFDCEBEB), onTertiaryContainer=Color(0xFF224B52),
    background=Color(0xFFF5F3EA), onBackground=Color(0xFF20372F),
    surface=Color(0xFFFFFDF6), onSurface=Color(0xFF20372F),
    surfaceVariant=Color(0xFFEAEDE2), onSurfaceVariant=Color(0xFF55645B),
    outline=Color(0xFF78867C), outlineVariant=Color(0xFFD9DFD3),
    error=Color(0xFFA43D31), errorContainer=Color(0xFFFBE4DC), onErrorContainer=Color(0xFF6E271F),
)
private val Night = darkColorScheme(
    primary=Color(0xFFA3D7B6), onPrimary=Color(0xFF103A2B),
    primaryContainer=Color(0xFF244D3C), onPrimaryContainer=Color(0xFFD3EED8),
    secondary=Color(0xFFF1BA8C), onSecondary=Color(0xFF472C19),
    secondaryContainer=Color(0xFF553D2C), onSecondaryContainer=Color(0xFFF9DFC3),
    tertiary=Color(0xFFA2CDD2), tertiaryContainer=Color(0xFF2D4D53), onTertiaryContainer=Color(0xFFD2EDF1),
    background=Color(0xFF101D18), onBackground=Color(0xFFE2E9DE),
    surface=Color(0xFF192820), onSurface=Color(0xFFE2E9DE),
    surfaceVariant=Color(0xFF2B3B31), onSurfaceVariant=Color(0xFFBAC9BD),
    outline=Color(0xFF8D9E92), outlineVariant=Color(0xFF3B4C40),
    error=Color(0xFFFFB4A4), errorContainer=Color(0xFF633329), onErrorContainer=Color(0xFFFFDAD1),
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
    val colors=if(isSystemInDarkTheme()) Night else Day
    MaterialTheme(colorScheme=colors,typography=DriveTypography,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(30.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground,content=content)
    }
}
