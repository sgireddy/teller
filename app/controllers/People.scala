package controllers

import models.{ Organisation, Activity, Address, Person }
import org.joda.time.DateTime
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.Constraints
import play.api.i18n.Messages
import securesocial.core.{ SecureSocial, SecuredRequest }
import scala.Predef._

object People extends Controller with SecureSocial {

  /**
   * HTML form mapping for a person’s address.
   */
  val addressMapping = mapping(
    "street1" -> optional(text),
    "street2" -> optional(text),
    "city" -> optional(text),
    "province" -> optional(text),
    "postCode" -> optional(text),
    "country" -> nonEmptyText)(Address.apply)(Address.unapply)

  /**
   * HTML form mapping for creating and editing.
   */
  def personForm(request: SecuredRequest[_]) = Form(mapping(
    "id" -> ignored(Option.empty[Long]),
    "firstName" -> nonEmptyText,
    "lastName" -> nonEmptyText,
    "emailAddress" -> email,
    "address" -> addressMapping,
    "bio" -> optional(text),
    "interests" -> optional(text),
    "twitterHandle" -> optional(text.verifying(Constraints.pattern("""[A-Za-z0-9_]{1,16}""".r, error = "error.twitter"))),
    "facebookUrl" -> optional(text),
    "linkedInUrl" -> optional(text),
    "googlePlusUrl" -> optional(text),
    "boardMember" -> default(boolean, false),
    "stakeholder" -> default(boolean, true),
    "active" -> ignored(true),
    "created" -> ignored(DateTime.now()),
    "createdBy" -> ignored(request.user.fullName)) (Person.apply)(Person.unapply))

  /**
   * Form target for toggling whether an organisation is active.
   */
  def activation(id: Long) = SecuredAction {
    implicit request ⇒

      Person.find(id).map { person ⇒
        Form("active" -> boolean).bindFromRequest.fold(
          form ⇒ {
            BadRequest("invalid form data")
          },
          active ⇒ {
            Person.activate(id, active)
            Activity.insert(request.user.fullName, if (active) Activity.Predicate.Activated else Activity.Predicate.Deactivated, person.fullName)
            val message = Messages("success.activate." + active.toString, Messages("models.Person"), person.fullName)
            Redirect(routes.People.details(id)).flashing("success" -> message)
          })
      } getOrElse {
        Redirect(routes.People.index).flashing("error" -> Messages("error.notFound", Messages("models.Organisation")))
      }
  }

  /**
   * Create page.
   */
  def add = SecuredAction { implicit request ⇒
    Ok(views.html.person.form(request.user, None, personForm(request)))
  }

  /**
   * Create form submits to this action.
   */
  def create = SecuredAction { implicit request ⇒
    personForm(request).bindFromRequest.fold(
      formWithErrors ⇒
        BadRequest(views.html.person.form(request.user, None, formWithErrors)),
      person ⇒ {
        val updatedPerson = person.save
        Activity.insert(request.user.fullName, Activity.Predicate.Created, updatedPerson.fullName)
        val message = Messages("success.insert", Messages("models.Person"), updatedPerson.fullName)
        Redirect(routes.People.index()).flashing("success" -> message)
      })
  }

  /**
   * Deletes an person.
   */
  def delete(id: Long) = SecuredAction { request ⇒
    Person.find(id).map { person ⇒
      Person.delete(id)
      Activity.insert(request.user.fullName, Activity.Predicate.Deleted, person.fullName)
      val message = Messages("success.delete", Messages("models.Person"), person.fullName)
      Redirect(routes.People.index).flashing("success" -> message)
    }.getOrElse(NotFound)
  }

  /**
   * Details page.
   * @param id Person ID
   */
  def details(id: Long) = SecuredAction { implicit request ⇒
    models.Person.find(id).map { person ⇒
      Ok(views.html.person.details(request.user, person, person.membership))
    } getOrElse {
      Redirect(routes.People.index).flashing("error" -> Messages("error.person.notFound"))
    }
  }

  def index = SecuredAction { implicit request ⇒
    val people = models.Person.findAll
    Ok(views.html.person.index(request.user, people))
  }

}