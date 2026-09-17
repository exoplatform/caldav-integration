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
package org.exoplatform.caldav.service;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

/**
 * What the server itself says about who owns each calendar an account sees
 * — the fourth witness {@code CaldavOutboundService#ownershipOf} hears
 * (EXO-90347), asked at most once per account per pass.
 *
 * <p>
 * The three other witnesses cannot tell a colleague's calendar the account
 * subscribed to from the account's own when neither was minted by this
 * deployment: on BlueMind the DAV listing puts the subscribed calendar under
 * the account's own home and names the account as its owner, the slug names
 * an anchor nobody here holds, and the container uid is eXo's shape rather
 * than one that carries an owner. So a calendar a colleague had imported
 * from elsewhere and then shared was adopted as the sharee's own — observed
 * live as eric's "Perso" coming back as root's calendar 24. BlueMind's
 * subscription listing carries the owner of every calendar in the account's
 * view; this is that listing, reduced to the one question the classifier
 * asks of it.
 *
 * <p>
 * <b>Four words, and what each costs the classifier.</b>
 * {@link Word#SILENT} is a server that was not asked — one that is not
 * BlueMind's, where the current behaviour is kept unchanged.
 * {@link Word#ACCOUNTS_OWN} and {@link Word#ANOTHERS} are answers.
 * {@link Word#UNKNOWN} is a question that could not be answered: the listing
 * could not be fetched, the session was not the account's, or the calendar
 * is simply not in it. The classifier fails closed on it for adoption alone
 * — adopting on missing evidence is exactly the defect — and leaves the
 * calendar for the next pass.
 *
 * <p>
 * <b>Deferred, and memoised.</b> The listing costs a REST login, a GET and a
 * logout, while most passes never need it: every collection a pass
 * classifies is settled by the user's own pairs, the deployment's pairs or
 * the naming before this witness is heard. So the listing is fetched the
 * first time a question reaches it and kept for the rest of the pass —
 * one fetch per account per pass at most, none when nothing asks — which is
 * the bound the sweep is held to. Across passes the fetch is served from
 * {@code CaldavServerOwnerStorage}'s cache, so most first questions cost no
 * request either.
 *
 * <p>
 * <b>One refresh per pass, for a calendar the listing does not name.</b> A
 * cached listing predates every share made since it was written, and a new
 * share met as "not in the listing" would sit unclassified — neither
 * adopted nor listed as shared — until the entry expired. So a deferred
 * witness may carry a second supplier: heard once per pass, the first time
 * a question about a calendar the <em>present</em> listing does not name
 * comes in, and never for a listing that could not be had at all (a
 * failed fetch is one attempt per pass, as before the cache). A refresh
 * that yields nothing keeps the listing already in hand.
 *
 * <p>
 * Services-local like {@link CollectionOwnership}: an answer held for the
 * length of one pass or one listing, persisted nowhere.
 */
public final class AccountCalendarOwners {

  /**
   * What the server said about one calendar.
   */
  public enum Word {

    /** The server was not asked: not BlueMind, current behaviour kept. */
    SILENT,

    /** The server could not be asked, or does not list the calendar. */
    UNKNOWN,

    /** The calendar's owner is the account itself. */
    ACCOUNTS_OWN,

    /** The calendar's owner is another directory entry. */
    ANOTHERS
  }

  /**
   * The server's word on one calendar, and the owner it named.
   *
   * @param word what the server said
   * @param ownerUid the owner's directory entry uid for {@link Word#ANOTHERS};
   *          null otherwise
   */
  public record Verdict(Word word, String ownerUid) {
  }

  /** The one witness for a server that is not asked. */
  private static final AccountCalendarOwners SILENT_OWNERS      = new AccountCalendarOwners(Word.SILENT, null, null, null, null);

  /** The one witness for a server that was asked and could not answer. */
  private static final AccountCalendarOwners UNAVAILABLE_OWNERS = new AccountCalendarOwners(Word.UNKNOWN, null, null, null, null);

  /** The word every question gets, for a witness that has no listing. */
  private final Word                         blanket;

  /** The account's own uid, which a listed owner is compared against. */
  private final String                       accountUid;

  /** The listing: the owner uid of each calendar, by container uid. */
  private final Map<String, String>          ownerByContainerUid;

  /** The fetch a deferred witness runs on its first question; else null. */
  private final Supplier<AccountCalendarOwners> deferred;

  /**
   * The one refresh a deferred witness may run for a calendar its listing
   * does not name; null when it has none, or is not deferred.
   */
  private final Supplier<AccountCalendarOwners> refresh;

  /** What the fetch produced, once it has run. */
  private AccountCalendarOwners                 resolved;

  /** Whether the refresh has been heard: once per witness, whatever it said. */
  private boolean                               refreshed;

  /**
   * A witness in one of its shapes.
   *
   * @param blanket the word every question gets when there is no listing;
   *          null when there is one
   * @param accountUid the account's own uid, when there is a listing
   * @param ownerByContainerUid the listing, keyed by container uid
   * @param deferred the fetch to run on the first question, for a deferred
   *          witness; null otherwise
   * @param refresh the fetch to run once for a calendar the listing does not
   *          name; null for none
   */
  private AccountCalendarOwners(Word blanket,
                                String accountUid,
                                Map<String, String> ownerByContainerUid,
                                Supplier<AccountCalendarOwners> deferred,
                                Supplier<AccountCalendarOwners> refresh) {
    this.blanket = blanket;
    this.accountUid = accountUid;
    this.ownerByContainerUid = ownerByContainerUid == null ? null : Map.copyOf(ownerByContainerUid);
    this.deferred = deferred;
    this.refresh = refresh;
  }

  /**
   * A server that is not asked: every calendar is {@link Word#SILENT}, and
   * the classifier behaves as it did before this witness existed.
   *
   * @return the silent witness
   */
  public static AccountCalendarOwners silent() {
    return SILENT_OWNERS;
  }

  /**
   * A server that was asked and could not answer: every calendar is
   * {@link Word#UNKNOWN}.
   *
   * @return the unavailable witness
   */
  public static AccountCalendarOwners unavailable() {
    return UNAVAILABLE_OWNERS;
  }

  /**
   * A listing in hand.
   *
   * @param accountUid the directory entry uid the listing was read as; a
   *          blank uid makes every calendar {@link Word#UNKNOWN}, since an
   *          owner cannot be compared to nobody
   * @param ownerByContainerUid the owner uid of each listed calendar, keyed
   *          by container uid
   * @return the witness
   */
  public static AccountCalendarOwners of(String accountUid, Map<String, String> ownerByContainerUid) {
    if (StringUtils.isBlank(accountUid) || ownerByContainerUid == null) {
      return UNAVAILABLE_OWNERS;
    }
    return new AccountCalendarOwners(null, accountUid, ownerByContainerUid, null, null);
  }

  /**
   * A listing fetched on the first question and kept for every later one.
   *
   * <p>
   * The supplier runs at most once, however many calendars are asked about
   * and whatever it answers — an unavailable listing is not fetched again
   * within the pass either. A supplier that throws is read as
   * {@link #unavailable()}; the caller that built it is expected to have
   * caught and said what it wanted to say.
   *
   * @param fetch how to get the listing
   * @return the deferred witness, with no refresh
   */
  public static AccountCalendarOwners deferred(Supplier<AccountCalendarOwners> fetch) {
    return deferred(fetch, null);
  }

  /**
   * A listing fetched on the first question, kept for every later one, and
   * read again at most once for a calendar it does not name.
   *
   * <p>
   * The refresh runs the first time a question about an absent calendar
   * reaches a listing that is <em>present</em> — never when the fetch
   * failed, since a failure is one attempt per pass — and whatever it
   * answers it is not run again in this witness's life. A refresh that
   * answers null, or throws, leaves the listing in hand as it was.
   *
   * @param fetch how to get the listing
   * @param refresh how to read it again, fresh from the server; null for no
   *          refresh
   * @return the deferred witness
   */
  public static AccountCalendarOwners deferred(Supplier<AccountCalendarOwners> fetch, Supplier<AccountCalendarOwners> refresh) {
    return new AccountCalendarOwners(null, null, null, fetch, refresh);
  }

  /**
   * The server's word on one calendar.
   *
   * @param containerUid the calendar's container uid, the last segment of
   *          its collection path
   * @return the verdict, never null
   */
  public Verdict ownerOf(String containerUid) {
    AccountCalendarOwners listing = resolve();
    Verdict verdict = listing.verdictOn(containerUid);
    if (verdict.word() == Word.UNKNOWN && listing.blanket == null && StringUtils.isNotBlank(containerUid)) {
      // Present, and silent about this calendar: the one case a refresh can
      // change, and the one case it is spent on.
      AccountCalendarOwners fresher = refreshOnce();
      if (fresher != null) {
        verdict = fresher.verdictOn(containerUid);
      }
    }
    return verdict;
  }

  /**
   * This listing's word on one calendar, with no refresh.
   *
   * @param containerUid the calendar's container uid
   * @return the verdict
   */
  private Verdict verdictOn(String containerUid) {
    if (blanket != null) {
      return new Verdict(blanket, null);
    }
    String owner = StringUtils.isBlank(containerUid) ? null : ownerByContainerUid.get(containerUid);
    if (StringUtils.isBlank(owner)) {
      return new Verdict(Word.UNKNOWN, null);
    }
    return StringUtils.equalsIgnoreCase(owner, accountUid) ? new Verdict(Word.ACCOUNTS_OWN, null) : new Verdict(Word.ANOTHERS, owner);
  }

  /**
   * Hears the refresh, the first time and never again; a fresh listing
   * replaces the one in hand.
   *
   * @return the fresh listing, or null when there is no refresh, it was
   *         already heard, or it answered nothing
   */
  private synchronized AccountCalendarOwners refreshOnce() {
    if (refresh == null || refreshed) {
      return null;
    }
    refreshed = true;
    try {
      AccountCalendarOwners fresher = refresh.get();
      if (fresher == null) {
        return null;
      }
      resolved = fresher.resolve();
      return resolved;
    } catch (RuntimeException e) {
      return null;
    }
  }


  /**
   * The listing behind this witness, fetched now if it was deferred.
   *
   * @return itself, or the listing the fetch produced
   */
  private synchronized AccountCalendarOwners resolve() {
    if (deferred == null) {
      return this;
    }
    if (resolved == null) {
      Supplier<AccountCalendarOwners> fetch = deferred;
      try {
        AccountCalendarOwners fetched = fetch.get();
        resolved = fetched == null ? UNAVAILABLE_OWNERS : fetched.resolve();
      } catch (RuntimeException e) {
        resolved = UNAVAILABLE_OWNERS;
      }
    }
    return resolved;
  }
}
