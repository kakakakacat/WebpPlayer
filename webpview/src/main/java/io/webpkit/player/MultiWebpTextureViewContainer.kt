package io.webpkit.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

open class MultiWebpTextureViewContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val textureView: MultiWebpTextureView = MultiWebpTextureView(context)

    init {
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }
}
