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

package models

import models.database._
import org.joda.time.DateTime
import play.api.db.slick.Config.driver.simple._
import play.api.db.slick.DB
import play.api.db.slick.DB.withSession
import play.api.Play.current
import scala.slick.lifted.Query
import scala.Some
import JodaMoney._

/**
 * A person, such as the owner or employee of an organisation.
 */
case class Person(
  id: Option[Long],
  firstName: String,
  lastName: String,
  emailAddress: String,
  address: Address,
  bio: Option[String],
  interests: Option[String],
  twitterHandle: Option[String],
  facebookUrl: Option[String],
  linkedInUrl: Option[String],
  googlePlusUrl: Option[String],
  boardMember: Boolean = false,
  stakeholder: Boolean = true,
  webSite: Option[String],
  blog: Option[String],
  active: Boolean = true,
  created: DateTime = DateTime.now(),
  createdBy: String,
  updated: DateTime,
  updatedBy: String) extends AccountHolder {

  def fullName: String = firstName + " " + lastName

  def name = fullName

  def fullNamePossessive = if (lastName.endsWith("s")) s"$fullName’" else s"$fullName’s"

  /**
   * Associates this person with given organisation.
   */
  def addMembership(organisationId: Long): Unit = withSession { implicit session ⇒
    OrganisationMemberships.forInsert.insert(this.id.get, organisationId)
  }

  /**
   * Returns true if this person may be deleted.
   */
  lazy val canDelete: Boolean = account.canDelete && contributions.isEmpty && memberships.isEmpty && licenses.isEmpty

  /**
   * Removes this person’s membership in the given organisation.
   */
  def deleteMembership(organisationId: Long): Unit = withSession { implicit session ⇒
    OrganisationMemberships.filter(membership ⇒ membership.personId === id && membership.organisationId === organisationId).mutate(_.delete)
  }

  /**
   * Returns a user for a different person to this one who has the same Twitter handle, if there is one.
   * This is used to check for duplicate Twitter handles when creating accounts.
   */
  def findUserWithSameTwitter: Option[UserAccount] = {
    val userSameTwitter = this.twitterHandle.map(handle ⇒ UserAccount.findByTwitterHandle(handle)).flatten
    if (userSameTwitter.map(_.personId) == this.id) None else userSameTwitter
  }

  /**
   * Returns a list of this person’s content licenses.
   */
  lazy val licenses: List[LicenseView] = withSession { implicit session ⇒

    val query = for {
      license ← Licenses if license.licenseeId === this.id
      brand ← license.brand
    } yield (license, brand)

    query.sortBy(_._2.name.toLowerCase).list.map {
      case (license, brand) ⇒ LicenseView(brand, license)
    }
  }

  /**
   * Returns a list of this person's contributions.
   */
  lazy val contributions: List[ContributionView] = withSession { implicit session ⇒
    Contribution.contributions(this.id.get, true)
  }

  /**
   * Returns a list of the organisations this person is a member of.
   */
  lazy val memberships: List[Organisation] = withSession { implicit session ⇒
    val query = for {
      membership ← OrganisationMemberships if membership.personId === this.id
      organisation ← membership.organisation
    } yield organisation
    query.sortBy(_.name.toLowerCase).list
  }

  /**
   * Inserts this person into the database and returns the saved Person, with the ID added.
   */
  def insert: Person = DB.withSession { implicit session ⇒
    val newAddress = Address.insert(this.address)
    val personId = People.forInsert.insert(this.copy(address = newAddress))
    Accounts.insert(Account(personId = Some(personId)))
    this.copy(id = Some(personId))
  }

  /**
   * Updates this person in the database and returns the saved person.
   */
  def update: Person = DB.withSession { implicit session ⇒
    session.withTransaction {

      val addressId = People.filter(_.id === this.id).map(_.addressId).first

      val addressQuery = for {
        address ← Addresses if address.id === addressId
      } yield address
      addressQuery.update(address.copy(id = Some(addressId)))

      // Skip the id, created, createdBy and active fields.
      val personUpdateTuple = (firstName, lastName, emailAddress, bio, interests, twitterHandle, facebookUrl,
        linkedInUrl, googlePlusUrl, boardMember, stakeholder, webSite, blog, updated, updatedBy)
      val updateQuery = People.filter(_.id === id).map(_.forUpdate)
      updateQuery.update(personUpdateTuple)
      this
    }
  }

  /**
   * Finds the active `Account`s that this `Person` has access rights to.
   *
   * Currently, ‘having access rights to an account’ means that:
   *  - This person is the account‘s holder
   *  - This person is a member of the organisation that is the account’s holder
   *
   * @return The list of accounts that this person has access to
   */
  def findAccessibleAccounts: List[AccountSummary] = withSession { implicit session ⇒
    val query = for {
      account ← Accounts if account.active
      organisation ← Organisations if account.organisationId === organisation.id.?
      membership ← OrganisationMemberships if membership.organisationId === organisation.id
      if membership.personId.? === this.id
    } yield (account.id, organisation.name, account.currency, account.active)

    account.summary :: query.list.map(AccountSummary.tupled)
  }

  def summary: PersonSummary = PersonSummary(id.get, firstName, lastName, active, address.countryCode)
}

case class PersonSummary(id: Long, firstName: String, lastName: String, active: Boolean, countryCode: String)

object Person {

  /**
   * Activates the organisation, if the parameter is true, or deactivates it.
   */
  def activate(id: Long, active: Boolean): Unit = withSession { implicit session ⇒
    val query = for {
      person ← People if person.id === id
    } yield person.active
    query.update(active)
  }

  /**
   * Deletes the person with the given ID and their account.
   */
  def delete(id: Long): Unit = withSession { implicit session ⇒
    find(id).map(_.account).map(_.delete)
    People.where(_.id === id).mutate(_.delete())
  }

  def find(id: Long): Option[Person] = withSession { implicit session ⇒
    val query = for {
      person ← People if person.id === id
    } yield person

    query.firstOption
  }

  /** Finds all active people, filtered by stakeholder and/or board member status **/
  def findActive(stakeholdersOnly: Boolean, boardMembersOnly: Boolean): List[Person] = withSession { implicit session ⇒
    val baseQuery = Query(People).filter(_.active === true).sortBy(_.firstName.toLowerCase)
    val stakeholderFilteredQuery = if (stakeholdersOnly) baseQuery.filter(_.stakeholder) else baseQuery
    val boardMembersFilteredQuery = if (boardMembersOnly) stakeholderFilteredQuery.filter(_.boardMember) else stakeholderFilteredQuery

    boardMembersFilteredQuery.list
  }

  /** Retrieves a list of all people from the database **/
  def findAll: List[PersonSummary] = withSession { implicit session ⇒
    (for {
      person ← People
      address ← Addresses if person.addressId === address.id
    } yield (person.id, person.firstName, person.lastName, person.active, address.countryCode))
      .sortBy(_._2.toLowerCase)
      .mapResult(PersonSummary.tupled).list
  }

  def findActive: List[Person] = withSession { implicit session ⇒
    Query(People).filter(_.active === true).sortBy(_.firstName.toLowerCase).list
  }

}

