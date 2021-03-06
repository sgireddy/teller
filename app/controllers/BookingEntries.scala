/*
 * Happy Melly Teller
 * Copyright (C) 2013, Happy Melly http://www.happymelly.com
 *
 * This file is part of the Happy Melly Teller.
 *
 * Happy Melly Teller is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Happy Melly Teller is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Happy Melly Teller.  If not, see <http://www.gnu.org/licenses/>.
 *
 * If you have questions concerning this license or the applicable additional terms, you may contact
 * by email Sergey Kotlov, sergey.kotlov@happymelly.com or
 * in writing Happy Melly One, Handelsplein 37, Rotterdam, The Netherlands, 3071 PR
 */

package controllers

import controllers.Forms._
import models.JodaMoney._
import models.UserRole.Role._
import models._
import org.joda.money.{ CurrencyUnit, Money }
import org.joda.time.LocalDate
import play.api.mvc.Controller
import play.api.data.Forms._
import play.api.data.Form
import play.api.data.validation.Constraints._
import securesocial.core.SecuredRequest
import scala.util.Random
import play.api.i18n.Messages
import services.CurrencyConverter
import scala.concurrent.ExecutionContext.Implicits.global

object BookingEntries extends Controller with Security {

  def bookingEntryForm(implicit request: SecuredRequest[_]) = Form(mapping(
    "id" -> ignored(Option.empty[Long]),
    "ownerId" -> ignored(0L),
    "bookingNumber" -> ignored(Option.empty[Int]),
    "summary" -> nonEmptyText(maxLength = 50),
    "source" -> jodaMoney().verifying("error.money.negativeOrZero", (m: Money) ⇒ m.isPositive),
    "sourcePercentage" -> number(0, 100),
    "fromId" -> nonEmptyText.transform(_.toLong, (l: Long) ⇒ l.toString).verifying("error.account.noAccess", isAccessible(request, _)),
    "fromAmount" -> ignored(Money.zero(CurrencyUnit.EUR)),
    "toId" -> nonEmptyText.transform(_.toLong, (l: Long) ⇒ l.toString),
    "toAmount" -> ignored(Money.zero(CurrencyUnit.EUR)),
    "brandId" -> nonEmptyText.transform(_.toLong, (l: Long) ⇒ l.toString),
    "reference" -> optional(text(maxLength = 16)),
    "referenceDate" -> jodaLocalDate.verifying("error.date.future", (d: LocalDate) ⇒ d.isBefore(LocalDate.now.plusDays(1))),
    "description" -> optional(text(maxLength = 250)),
    "url" -> optional(webUrl),
    "transactionTypeId" -> optional(longNumber),
    "owes" -> boolean)(fromForm)(toForm))

  /**
   * Creates a `BookingEntry` from form data.
   */
  def fromForm(id: Option[Long], ownerId: Long, bookingNumber: Option[Int], summary: String, source: Money, sourcePercentage: Int,
    fromId: Long, fromAmount: Money, toId: Long, toAmount: Money,
    brandId: Long, reference: Option[String], referenceDate: LocalDate,
    description: Option[String], url: Option[String], transactionTypeId: Option[Long], owes: Boolean) = {

    BookingEntry(id, ownerId, LocalDate.now, bookingNumber, summary, if (owes) source else source.negated,
      sourcePercentage, fromId, fromAmount, toId, toAmount, brandId, reference, referenceDate, description, url,
      transactionTypeId)
  }

  /**
   * Creates a tuple of form data from a `BookingEntry`.
   */
  def toForm(e: BookingEntry) = Some((e.id, e.ownerId, e.bookingNumber, e.summary,
    if (e.source.isNegative) e.source.multipliedBy(-1L) else e.source,
    e.sourcePercentage, e.fromId, e.fromAmount, e.toId, e.toAmount, e.brandId, e.reference, e.referenceDate,
    e.description, e.url, e.transactionTypeId, e.source.isPositiveOrZero))

  def add = SecuredRestrictedAction(Viewer) { implicit request ⇒
    implicit handler ⇒
      val form = bookingEntryForm.fill(BookingEntry.blank)
      val currentUser = request.user.asInstanceOf[LoginIdentity].userAccount
      val (fromAccounts, toAccounts) = findFromAndToAccounts(currentUser)

      Ok(views.html.booking.form(request.user, form, fromAccounts, toAccounts, Brand.findAll, TransactionType.findAll))
  }

  /**
   * Creates a user from an ‘add form’ submission.
   */
  def create = SecuredRestrictedAction(Editor) { implicit request ⇒
    implicit handler ⇒

      bookingEntryForm(request).bindFromRequest.fold(
        formWithErrors ⇒ {
          val currentUser = request.user.asInstanceOf[LoginIdentity].userAccount
          val (fromAccounts, toAccounts) = findFromAndToAccounts(currentUser)
          val brands = Brand.findAll
          val transactionTypes = TransactionType.findAll
          BadRequest(views.html.booking.form(request.user, formWithErrors, fromAccounts, toAccounts, brands, transactionTypes))
        },
        entry ⇒ Async {
          entry.withSourceConverted.map { entry ⇒
            val currentUser = request.user.asInstanceOf[LoginIdentity].userAccount
            val updatedEntry = entry.copy(ownerId = currentUser.personId).insert
            val activityObject = Messages("models.BookingEntry.name", entry.source.abs.toString)
            val activity = Activity.insert(request.user.fullName, Activity.Predicate.Created, activityObject)
            Redirect(routes.BookingEntries.index()).flashing("success" -> activity.toString)
          }
        })
  }

  def index = SecuredRestrictedAction(Viewer) { implicit request ⇒
    implicit handler ⇒
      val levy = Account.find(Levy)
      val levySummary = if (levy.active) Some(levy.summary) else None
      Ok(views.html.booking.index(request.user, None, levySummary, BookingEntry.findAll))
  }

  def details(bookingNumber: Int) = SecuredRestrictedAction(Viewer) { implicit request ⇒
    implicit handler ⇒
      BookingEntry.findByBookingNumber(bookingNumber).map{ bookingEntry ⇒
        Ok(views.html.booking.details(request.user, bookingEntry))
      }.getOrElse(NotFound)
  }

  private def findFromAndToAccounts(user: UserAccount): (List[AccountSummary], List[AccountSummary]) = {
    val allActive: List[AccountSummary] = Account.findAllActive
    if (user.getRoles.contains(Editor)) {
      (allActive, allActive)
    } else {
      val person: Option[Person] = user.person
      val accessible: List[AccountSummary] = person.map(_.findAccessibleAccounts).toList.flatten
      (accessible, allActive)
    }
  }

  private def isAccessible(request: SecuredRequest[_], accountId: Long): Boolean = {
    val person = request.user.asInstanceOf[LoginIdentity].userAccount.person
    val accessibleAccountIds = person.map(_.findAccessibleAccounts.map(_.id)).toList.flatten
    accessibleAccountIds.contains(accountId)
  }

}
