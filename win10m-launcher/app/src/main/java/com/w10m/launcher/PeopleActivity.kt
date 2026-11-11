package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.icu.text.AlphabeticIndex
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * People in the W10M layout: searchable contact list grouped by letter (accent-coloured headers, round
 * avatars), and a contact card with call / text / e-mail rows. Reads the system contacts; adding and
 * editing are handed to the system contacts editor.
 */
class PeopleActivity : Activity() {

    private class Person(val id: Long, val name: String, val photo: String?)
    private sealed class Row { class Header(val label: String) : Row(); class Item(val p: Person) : Row() }

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var listPage: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var search: MetroSearchBox
    private lateinit var empty: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var cardScroll: ScrollView
    private var people: List<Person> = emptyList()
    private var rows: List<Row> = emptyList()
    private val photos = HashMap<String, Bitmap?>()
    private val index = AlphabeticIndex<Any>(Locale.CHINA).addLabels(Locale.ENGLISH).addLabels(Locale.getDefault())
        .setMaxLabelCount(300).buildImmutableIndex()
    private var openId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        // The list holds focus at rest so the search box doesn't light up and raise the keyboard on launch.
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg); isFocusableInTouchMode = true }
        val m = dpi(Metro.PAGE_MARGIN)

        listPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listPage.addView(title(getString(R.string.people)))
        search = MetroSearchBox(this, p)
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })
        listPage.addView(search, LinearLayout.LayoutParams(-1, dpi(32f)).apply { setMargins(m, 0, m, dpi(8f)) })
        val body = FrameLayout(this)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); adapter = Adapter(); itemAnimator = null }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dpi(8f), m, 0) }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        listPage.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        // Command bar: "+" new contact.
        listPage.addView(LinearLayout(this).apply {
            setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_HORIZONTAL
            addView(GlyphButton(context, p.fg) { c, pt, cx, cy, u ->
                c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, pt); c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, pt)
            }.apply { setOnClickListener { go(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)) } }, LinearLayout.LayoutParams(dpi(72f), -1))
        }, LinearLayout.LayoutParams(-1, dpi(48f)))
        root.addView(listPage, FrameLayout.LayoutParams(-1, -1))

        card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dpi(24f)) }
        cardScroll = ScrollView(this).apply { addView(card); setBackgroundColor(p.bg); visibility = View.GONE; overScrollMode = View.OVER_SCROLL_NEVER; isClickable = true }
        root.addView(cardScroll, FrameLayout.LayoutParams(-1, -1))
        shell.setContent(root)
        root.requestFocus()
    }

    override fun onResume() { super.onResume(); load(); if (cardScroll.visibility == View.VISIBLE) showCard(openId) }

    private fun has() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun go(i: Intent) = try { startActivity(i) } catch (_: Exception) {}

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Metro.semibold(context); setTextColor(p.fg); isAllCaps = true
        setPadding(dpi(Metro.PAGE_MARGIN), dpi(10f), dpi(Metro.PAGE_MARGIN), dpi(10f))
    }

    // ---------------------------------------------------------------- list

    private fun load() {
        empty.removeAllViews()
        if (!has()) {
            people = emptyList(); rebuild()
            empty.addView(TextView(this).apply {
                text = getString(R.string.contacts_permission); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            })
            empty.addView(metroButton(this, p, getString(R.string.grant)) { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) },
                LinearLayout.LayoutParams(-2, dpi(32f)).apply { topMargin = dpi(12f) })
            return
        }
        val out = ArrayList<Person>()
        try {
            contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                null, null, null)?.use { c ->
                while (c.moveToNext()) { val n = c.getString(1); if (!n.isNullOrBlank()) out += Person(c.getLong(0), n, c.getString(2)) }
            }
        } catch (_: Exception) {}
        people = out
        rebuild()
        if (out.isEmpty()) empty.addView(TextView(this).apply {
            text = getString(R.string.no_contacts); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = load()

    private fun bucket(name: String): String {
        val b = index.getBucket(index.getBucketIndex(name))
        return if (b.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) b.label else "#"
    }

    private fun rebuild() {
        val q = search.edit.text.toString().trim()
        val out = ArrayList<Row>()
        if (q.isNotEmpty()) people.filter { it.name.contains(q, true) }.forEach { out += Row.Item(it) }
        else {
            val groups = sortedMapOf<Int, MutableList<Person>>()
            for (x in people) groups.getOrPut(index.getBucketIndex(x.name)) { mutableListOf() } += x
            val collator = java.text.Collator.getInstance(Locale.CHINA)
            for ((_, g) in groups) {
                out += Row.Header(bucket(g.first().name))
                g.sortedWith { a, b -> collator.compare(a.name, b.name) }.forEach { out += Row.Item(it) }
            }
        }
        rows = out
        list.adapter?.notifyDataSetChanged()
    }

    private fun photo(uri: String?): Bitmap? {
        if (uri == null) return null
        return photos.getOrPut(uri) {
            try { contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = PersonRow(this@PeopleActivity)
            v.layoutParams = RecyclerView.LayoutParams(-1, dpi(if (viewType == 0) 40f else 52f))
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView as PersonRow
            when (val r = rows[position]) {
                is Row.Header -> { v.header = r.label; v.person = null; v.setOnClickListener(null); v.isClickable = false }
                is Row.Item -> { v.header = null; v.person = r.p; v.setOnClickListener { showCard(r.p.id) } }
            }
            v.invalidate()
        }
    }

    /** Header: accent letter, 20 epx. Contact: 40 epx round avatar (photo or initial on accent), name 20 SemiLight. */
    private inner class PersonRow(c: Context) : View(c) {
        var header: String? = null
        var person: Person? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(20f); typeface = Metro.regular(c) }
        private val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(20f); typeface = Metro.semilight(c) }
        private val ip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(17f); typeface = Metro.regular(c); textAlign = Paint.Align.CENTER; color = Color.WHITE }
        override fun onDraw(cv: Canvas) {
            val m = dp(Metro.PAGE_MARGIN); val cy = height / 2f
            header?.let { hp.color = p.accent; cv.drawText(it, m, cy - (hp.ascent() + hp.descent()) / 2, hp); return }
            val x = person ?: return
            drawAvatar(cv, x, m + dp(20f), cy, dp(20f), fill, ip)
            np.color = p.fg
            val tx = m + dp(52f)
            val t = TextUtils.ellipsize(x.name, np, width - tx - m, TextUtils.TruncateAt.END)
            cv.drawText(t, 0, t.length, tx, cy - (np.ascent() + np.descent()) / 2, np)
        }
    }

    private fun drawAvatar(cv: Canvas, x: Person, cx: Float, cy: Float, r: Float, fill: Paint, ip: TextPaint) {
        val b = photo(x.photo)
        if (b != null) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = 2 * r / minOf(b.width, b.height)
            sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(s, s); postTranslate(cx - b.width * s / 2, cy - b.height * s / 2) })
            fill.shader = sh; cv.drawCircle(cx, cy, r, fill); fill.shader = null
        } else {
            fill.color = p.accent; cv.drawCircle(cx, cy, r, fill)
            cv.drawText(x.name.take(1).uppercase(), cx, cy - (ip.ascent() + ip.descent()) / 2, ip)
        }
    }

    // ---------------------------------------------------------------- contact card

    private fun showCard(id: Long) {
        val x = people.firstOrNull { it.id == id } ?: run { cardScroll.visibility = View.GONE; return }
        openId = id
        card.removeAllViews()
        val m = dpi(Metro.PAGE_MARGIN)
        card.addView(title(getString(R.string.people)))
        // Avatar + name.
        card.addView(object : View(this) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val ip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(34f); typeface = Metro.light(context); textAlign = Paint.Align.CENTER; color = Color.WHITE }
            override fun onDraw(cv: Canvas) = drawAvatar(cv, x, dp(Metro.PAGE_MARGIN) + dp(40f), height / 2f, dp(40f), fill, ip)
        }, LinearLayout.LayoutParams(-1, dpi(96f)))
        card.addView(TextView(this).apply {
            text = x.name; textSize = 34f; typeface = Metro.light(context); setTextColor(p.fg); includeFontPadding = false
            setPadding(m, dpi(4f), m, dpi(12f))
        })
        fun action(label: String, value: String, tap: () -> Unit) = card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(m, dpi(8f), m, dpi(8f))
            addView(TextView(context).apply { text = label; textSize = 20f; typeface = Metro.semilight(context); setTextColor(p.fg) })
            addView(TextView(context).apply { text = value; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.accent) })
            setOnClickListener { tap() }
        })
        if (has()) try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", arrayOf(id.toString()), null)?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: continue
                    if (!seen.add(num.filter { it.isDigit() || it == '+' })) continue
                    val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, c.getInt(1), c.getString(2)).toString()
                    action(getString(R.string.call_type, type), num) {
                        go(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", num, null), this, DialerActivity::class.java))
                    }
                    action(getString(R.string.text_type, type), num) {
                        go(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", num, null), this, MessagingActivity::class.java))
                    }
                }
            }
            contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?", arrayOf(id.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val mail = c.getString(0) ?: continue
                    action(getString(R.string.send_email), mail) { go(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", mail, null))) }
                }
            }
        } catch (_: Exception) {}
        card.addView(metroButton(this, p, getString(R.string.edit_contact)) {
            go(Intent(Intent.ACTION_EDIT, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)))
        }, LinearLayout.LayoutParams(-2, dpi(32f)).apply { setMargins(m, dpi(16f), m, 0) })
        cardScroll.scrollTo(0, 0)
        cardScroll.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shell.handleBack() -> Unit
            cardScroll.visibility == View.VISIBLE -> cardScroll.visibility = View.GONE
            search.edit.text.isNotEmpty() -> search.edit.setText("")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
