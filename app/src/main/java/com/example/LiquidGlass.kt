package com.example

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * AGSL rim for the glass surfaces. A signed distance to the rounded rect gives
 * every edge pixel a real normal, so the specular band follows the curve of
 * the corners instead of a straight linear gradient across the whole shape.
 *
 * The highlight sits on the top-left edge (the same key light as the rest of
 * the chrome), a fainter counter-rim sits on the bottom-right, and a thin
 * glow falls off just inside the edge to suggest the glass having thickness.
 * Interior pixels return alpha 0, so the fill underneath (the blurred video)
 * is untouched.
 */
private const val LIQUID_GLASS_RIM_AGSL = """
uniform float2 size;
uniform float radius;
uniform float rimPx;
uniform float4 rimColor;

float sdRoundedBox(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float r = min(radius, min(halfSize.x, halfSize.y));
    float2 p = coord - halfSize;
    float d = sdRoundedBox(p, halfSize, r);
    if (d > 0.0) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }
    float depth = -d;

    float e = 0.75;
    float2 grad = float2(
        sdRoundedBox(p + float2(e, 0.0), halfSize, r) - sdRoundedBox(p - float2(e, 0.0), halfSize, r),
        sdRoundedBox(p + float2(0.0, e), halfSize, r) - sdRoundedBox(p - float2(0.0, e), halfSize, r)
    );
    float2 n = normalize(grad + float2(0.00001, 0.00001));

    float2 toLight = normalize(float2(-0.55, -0.85));
    float facing = dot(n, toLight);

    float rim = 1.0 - smoothstep(0.0, rimPx, depth);
    float glow = 1.0 - smoothstep(0.0, rimPx * 5.0, depth);
    float hot = rim * pow(max(facing, 0.0), 3.0);
    float counter = rim * pow(max(-facing, 0.0), 2.0) * 0.35;

    float a = rim * 0.22 + hot * 0.85 + counter + glow * 0.06;
    a = clamp(a, 0.0, 1.0) * rimColor.a;

    float3 col = mix(rimColor.rgb, float3(1.0, 1.0, 1.0), clamp(hot * 0.35, 0.0, 1.0));
    return half4(col, a);
}
"""

/**
 * Glass rim for a surface. [cornerRadius] = null means a fully round
 * (circle) shape. [rimColor] tints the highlight (amber for active states).
 * [fallbackBorder] is the original Brush border, used below API 33 where
 * AGSL is unavailable, so older devices keep the previous look.
 */
internal fun Modifier.liquidGlassRim(
    cornerRadius: Dp? = null,
    rimColor: Color = Color.White,
    fallbackBorder: Brush,
    fallbackWidth: Dp = 1.dp
): Modifier {
    if (Build.VERSION.SDK_INT >= 33) {
        return liquidGlassShaderRim(cornerRadius, rimColor)
    }
    val shape: Shape = cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape
    return this.border(fallbackWidth, fallbackBorder, shape)
}

@RequiresApi(33)
private fun Modifier.liquidGlassShaderRim(cornerRadius: Dp?, rimColor: Color): Modifier =
    this.drawWithCache {
        val shader = RuntimeShader(LIQUID_GLASS_RIM_AGSL)
        val radiusPx = cornerRadius?.toPx() ?: min(size.width, size.height) / 2f
        val rimPx = 1.5f.dp.toPx()
        val brush = ShaderBrush(shader)
        onDrawWithContent {
            drawContent()
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("radius", radiusPx)
            shader.setFloatUniform("rimPx", rimPx)
            shader.setFloatUniform(
                "rimColor",
                rimColor.red,
                rimColor.green,
                rimColor.blue,
                rimColor.alpha
            )
            drawRect(brush = brush)
        }
    }
