/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.caldav.utils;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Gives every remote calendar a colour, deterministically.
 *
 * <p>
 * A port of the browser connector's derivation, arithmetic included, because
 * the colour is what a user recognises a calendar by. Changing it would
 * silently repaint everyone's agenda on the day this ships — the same events,
 * the same calendars, different colours, with nothing to explain it.
 *
 * <p>
 * The server's own colour wins when it publishes one. Only when it does not is
 * a colour derived, from the collection path rather than from its position in
 * a listing: a calendar added or removed elsewhere must not shift the colours
 * of the others.
 */
public final class CalendarPalette {

  /** WCAG AA for normal text against white, which these colours sit on. */
  private static final double  MIN_CONTRAST_RATIO = 4.5;

  /** A six-digit hex colour, with an optional alpha pair some servers append. */
  private static final Pattern HEX_COLOUR         = Pattern.compile("^#?([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?$");

  private CalendarPalette() {
    // utility class
  }

  /**
   * The calendars in the order colours are derived from, which is not the
   * order a server happens to list them in.
   *
   * <p>
   * Sorted by href so that a position is a property of the account rather than
   * of one response: a server free to reorder its listing would otherwise
   * repaint the calendars between two reads.
   *
   * @param hrefs the collection hrefs
   * @return the same hrefs, in a stable order
   */
  public static List<String> inStableOrder(List<String> hrefs) {
    return hrefs.stream().sorted(Comparator.comparing(href -> StringUtils.defaultString(href))).toList();
  }

  /**
   * The colour a calendar is shown in.
   *
   * @param publishedColour what the server published, possibly absent
   * @param href the collection href
   * @param position the calendar's index in the stable order
   * @param total how many calendars the account holds
   * @return a hex colour that is always usable
   */
  public static String colourOf(String publishedColour, String href, int position, int total) {
    String published = normalise(publishedColour);
    return published != null ? published : derive(href, position, total);
  }

  /**
   * A published colour reduced to the form eXo uses, or null when it is not a
   * colour at all.
   *
   * @param value what the server published
   * @return {@code #RRGGBB} upper case, or null
   */
  public static String normalise(String value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = HEX_COLOUR.matcher(value.trim());
    // The alpha pair is dropped rather than honoured: a translucent event on a
    // calendar grid reads as a lighter event, not a transparent one.
    return matcher.matches() ? "#" + matcher.group(1).toUpperCase() : null;
  }

  /**
   * A colour derived from the collection itself, for a server that publishes
   * none.
   *
   * <p>
   * The hue spreads the account's calendars around the circle so neighbours
   * stay distinguishable, offset by a hash of the parent path so two accounts
   * do not end up with the same palette. Saturation and lightness come from
   * the href's own hash, then lightness is walked down until the colour has
   * enough contrast against white — a calendar nobody can read the name of is
   * not a colour scheme, it is a bug.
   *
   * @param href the collection href
   * @param position the calendar's index in the stable order
   * @param total how many calendars the account holds
   * @return the derived colour
   */
  private static String derive(String href, int position, int total) {
    String url = StringUtils.defaultString(href);
    int hash = hashOf(url);
    int count = Math.max(total, 1);
    int index = Math.max(position, 0);
    int offset = hashOf(url.replaceAll("[^/]+/?$", "")) % 360;
    int hue = (int) Math.round((offset + index * 360.0 / count) % 360);
    int saturation = 58 + (hash >>> 9) % 4 * 8;
    int lightness = 38 + (hash >>> 17) % 3 * 5;
    String colour = hslToHex(hue, saturation, lightness);
    while (lightness > 20 && contrastWithWhite(colour) < MIN_CONTRAST_RATIO) {
      lightness -= 2;
      colour = hslToHex(hue, saturation, lightness);
    }
    return colour;
  }

  /**
   * The browser's own string hash, reproduced exactly.
   *
   * <p>
   * Java's {@code String.hashCode} is a different function, and using it would
   * have given every calendar a different colour from the one its owner has
   * been looking at. The 32-bit overflow is deliberate: it is what the
   * JavaScript did, and the result has to match.
   *
   * @param value the text to hash
   * @return a non-negative hash
   */
  static int hashOf(String value) {
    int hash = 0;
    for (int i = 0; i < value.length(); i++) {
      hash = (hash << 5) - hash + value.charAt(i);
    }
    // Math.abs(Integer.MIN_VALUE) is still negative; the JavaScript could not
    // produce that value, so it is folded rather than propagated.
    return hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
  }

  /**
   * An HSL colour as hex.
   *
   * @param hue degrees around the circle
   * @param saturation percent
   * @param lightness percent
   * @return {@code #RRGGBB} upper case
   */
  static String hslToHex(int hue, int saturation, int lightness) {
    double l = lightness / 100.0;
    double a = saturation * Math.min(l, 1 - l) / 100.0;
    return String.format("#%s%s%s", channel(0, hue, l, a), channel(8, hue, l, a), channel(4, hue, l, a)).toUpperCase();
  }

  /**
   * One channel of an HSL conversion.
   *
   * @param n the channel's phase
   * @param hue degrees around the circle
   * @param l lightness, zero to one
   * @param a the chroma term
   * @return the channel as two hex digits
   */
  private static String channel(int n, int hue, double l, double a) {
    double k = (n + hue / 30.0) % 12;
    double value = l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)));
    return String.format("%02x", (int) Math.round(255 * value));
  }

  /**
   * The contrast ratio of a colour against white, per WCAG.
   *
   * @param colour a {@code #RRGGBB} colour
   * @return the ratio, one and above
   */
  static double contrastWithWhite(String colour) {
    double luminance = 0.2126 * linear(colour, 0) + 0.7152 * linear(colour, 1) + 0.0722 * linear(colour, 2);
    return 1.05 / (luminance + 0.05);
  }

  /**
   * One channel of a colour, linearised for the luminance formula.
   *
   * @param colour a {@code #RRGGBB} colour
   * @param index which channel
   * @return the linearised value
   */
  private static double linear(String colour, int index) {
    double value = Integer.parseInt(colour.substring(1 + index * 2, 3 + index * 2), 16) / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
