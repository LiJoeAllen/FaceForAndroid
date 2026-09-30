// TabIcons.kt —— 底部导航三枚矢量图标：检测（放大镜）、身份（人像）、相机。
// 手绘 material 风格路径，避免引入 material-icons 传递依赖。

package cn.heavenlybook.facemirror.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 按标准 24x24 视口构建一个填充路径图标。 */
private fun icon(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.Black),
                // 偶奇填充:material 图标的"环/孔"依赖子路径相减(如放大镜内圈)。
                pathFillType = PathFillType.EvenOdd,
            )
        }
    }.build()

/** 检测分析：放大镜。 */
val AnalyzeIcon: ImageVector = icon(
    "Analyze",
    "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 " +
        "5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z" +
        "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
)

/** 身份：人像剪影。 */
val IdentityIcon: ImageVector = icon(
    "Identity",
    "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 " +
        "-8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z",
)

/** 实时相机：机身（偶奇填充抠出镜头环）+ 镜头芯。 */
val CameraIcon: ImageVector = icon(
    "Camera",
    "M9,3L7.17,5H4C2.9,5 2,5.9 2,7v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 " +
        "-2,-2h-3.17L15,3H9zM12,17.5c-3.04,0 -5.5,-2.46 -5.5,-5.5S8.96,6.5 12,6.5 " +
        "17.5,8.96 17.5,12 15.04,17.5 12,17.5z",
    "M12,15c1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3 1.34,3 3,3z",
)
