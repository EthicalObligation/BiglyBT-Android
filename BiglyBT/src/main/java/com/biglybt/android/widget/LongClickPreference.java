/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.biglybt.android.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

public class LongClickPreference
	extends Preference
	implements PreferenceLongClickable
{
	private OnPreferenceClickListener onLongClickListener = null;

	public LongClickPreference(Context context, AttributeSet attrs,
			int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
	}

	public LongClickPreference(Context context, AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public LongClickPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public LongClickPreference(Context context) {
		super(context);
	}

	@Override
	public void onBindViewHolder(PreferenceViewHolder holder) {
		super.onBindViewHolder(holder);
		holder.itemView.setOnLongClickListener(v -> {
			if (onLongClickListener == null || !isEnabled() || !isSelectable()) {
				return false;
			}
			return onLongClickListener.onPreferenceClick(this);
		});
	}

	@Override
	public void setOnLongClickListener(
			OnPreferenceClickListener onLongClickListener) {
		this.onLongClickListener = onLongClickListener;
	}
}
