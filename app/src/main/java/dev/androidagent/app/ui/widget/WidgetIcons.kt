/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.app.ui.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/** The icon a stat card can name. An unknown name draws no icon. */
internal fun widgetIcon(name: String?): ImageVector? = when (name?.trim()?.lowercase()) {
    "check", "ok", "done", "success" -> Icons.Outlined.CheckCircle
    "target", "goal" -> Icons.Outlined.TrackChanges
    "bolt", "fast", "energy", "power" -> Icons.Outlined.Bolt
    "clock", "time", "schedule", "latency" -> Icons.Outlined.Schedule
    "up", "trend", "growth", "trending_up" -> Icons.Outlined.TrendingUp
    "down", "decline", "trending_down" -> Icons.Outlined.TrendingDown
    "star", "rating" -> Icons.Outlined.Star
    "heart", "favorite", "health" -> Icons.Outlined.Favorite
    "user", "person", "people" -> Icons.Outlined.Person
    "money", "dollar", "cost", "price" -> Icons.Outlined.AttachMoney
    "chart", "stats", "bar" -> Icons.Outlined.BarChart
    "warning", "warn" -> Icons.Outlined.Warning
    "info" -> Icons.Outlined.Info
    "error", "fail", "failed" -> Icons.Outlined.ErrorOutline
    "home" -> Icons.Outlined.Home
    "fire", "hot", "streak" -> Icons.Outlined.LocalFireDepartment
    "cloud", "weather" -> Icons.Outlined.Cloud
    "battery" -> Icons.Outlined.BatteryFull
    "wifi", "network" -> Icons.Outlined.Wifi
    "bell", "notification" -> Icons.Outlined.Notifications
    "calendar", "date" -> Icons.Outlined.CalendarMonth
    "mail", "email", "message" -> Icons.Outlined.Mail
    "shield", "security" -> Icons.Outlined.Shield
    "lock", "private" -> Icons.Outlined.Lock
    "speed", "gauge" -> Icons.Outlined.Speed
    "memory", "cpu" -> Icons.Outlined.Memory
    "storage", "disk" -> Icons.Outlined.Storage
    "cart", "shop", "shopping" -> Icons.Outlined.ShoppingCart
    "water", "drop" -> Icons.Outlined.WaterDrop
    "settings", "gear" -> Icons.Outlined.Settings
    else -> null
}
