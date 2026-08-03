package com.Johnny.wcx.features.items.contacts.hidecontacts

/**
 * Internal rule for matching SQL queries and generating filter conditions.
 * Used to extend the WCX HideContacts with additional SQL injection rules.
 */
internal data class SqlRule(
    val name: String,
    val matches: (String) -> Boolean,
    val condition: (Set<String>) -> String
)