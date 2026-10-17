package com.resqroute.feature.map.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.resqroute.R

/**
 * One choice in the report sheet: an icon over a label, with a check badge when chosen (XPH-93).
 *
 * ## Why a card and not a button
 *
 * The selected state here is a **surface** change — the fill, the border, a coloured glow and a
 * badge in the corner. A `MaterialButton` renders its own state layers and fights every one of those,
 * which is how the previous picker ended up with an opacity nudge as its only feedback and got
 * reported as "pressing those buttons does nothing". A card has one background and one border, both
 * of which this class owns outright.
 *
 * ## The four rules it keeps
 *
 * 1. **Selection is never colour alone.** The chosen card changes fill *and* carries a badge *and*
 *    gets a content description ending in the sanctioned word for it, so it is legible to a screen
 *    reader and in a screenshot read out of context.
 * 2. **A category keeps its semantic colour until it is chosen.** Passable stays green, caution
 *    amber, avoid red — that is the meaning of the choice, and washing it to grey would hide it.
 *    Choosing overrides with white, because the fill is already doing the "chosen" job.
 * 3. **The icon and the label are never truncated.** The label wraps inside the card and the icon is
 *    a vector, so a longer translation grows the card rather than clipping a category.
 * 4. **Both states announce themselves.** `isSelected` is set for accessibility services and for any
 *    state-list a future style might use.
 */
class SelectionCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val icon: ImageView
    private val label: TextView
    private val badge: ImageView
    private val borderPx: Int = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    init {
        LayoutInflater.from(context).inflate(R.layout.view_selection_card, this, true)
        icon = findViewById(R.id.selectionCardIcon)
        label = findViewById(R.id.selectionCardLabel)
        badge = findViewById(R.id.selectionCardBadge)

        radius = resources.getDimension(R.dimen.rq_card_radius)
        strokeWidth = borderPx
        // On the card, not only on the inner body: the card is the tap target, so its own height is
        // what has to be hittable.
        minimumHeight = resources.getDimensionPixelSize(R.dimen.rq_card_min_height)
        isClickable = true
        isFocusable = true
        cardElevation = 0f
        // The glow on a chosen card is the accent colour, not black: a shadow that matches the fill is
        // what makes the selection read as lit rather than merely raised. API 28+ only, and a plain
        // shadow on older devices - the state is still unmistakable without it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            outlineSpotShadowColor = ContextCompat.getColor(context, R.color.rq_accent)
            outlineAmbientShadowColor = ContextCompat.getColor(context, R.color.rq_accent)
        }
    }

    /**
     * Renders one choice.
     *
     * [semanticColor] is the colour of the icon while the card is **unchosen** — the meaning of the
     * option, not decoration. It is ignored once [selected], because a chosen card is accent-filled
     * and the icon must turn white to stay visible on it.
     */
    fun bind(
        @DrawableRes iconRes: Int,
        text: CharSequence,
        @ColorRes semanticColor: Int,
        selected: Boolean,
    ) {
        isSelected = selected
        icon.setImageResource(iconRes)
        label.text = text

        if (selected) {
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.rq_accent))
            strokeColor = ContextCompat.getColor(context, R.color.rq_accent)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.rq_white),
            )
            label.setTextColor(ContextCompat.getColor(context, R.color.rq_white))
            label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
            badge.visibility = VISIBLE
            cardElevation = 10f * resources.displayMetrics.density
            // The badge is the "chosen" marker, so it must not be read as a separate control.
            badge.contentDescription = null
            contentDescription = context.getString(R.string.rq_report_chosen, text)
        } else {
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.rq_card_surface))
            strokeColor = ContextCompat.getColor(context, R.color.rq_card_border)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, semanticColor),
            )
            label.setTextColor(ContextCompat.getColor(context, R.color.rq_text_primary))
            label.setTypeface(label.typeface, android.graphics.Typeface.NORMAL)
            badge.visibility = GONE
            cardElevation = 0f
            contentDescription = text
        }
    }
}
