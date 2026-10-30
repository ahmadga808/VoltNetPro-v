package com.subsmgr.app

/** المستخدم الحالي: مدير (كل الصلاحيات) أو موظف/محصّل (صلاحيات محدودة). */
object Session {
    @Volatile var role: String = "admin"
    @Volatile var name: String = ""
    /** المستخدم اختار المتابعة بدون حساب في هذه الجلسة. */
    @Volatile var skipLogin: Boolean = false
    /** يضبطه AppRoot: يعيد التطبيق لشاشة القفل لتبديل المستخدم. */
    var lock: (() -> Unit)? = null
    val isAdmin: Boolean get() = role == "admin"
}

/** إجراءات المشترك المسموحة للموظف. */
val STAFF_ACTIONS = setOf("renew", "pay", "stmt", "wa", "wa_exp", "loc", "read", "cut", "promise")
