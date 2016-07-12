package utils.auth

import com.mohiva.play.silhouette.api.Env
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import services.IdentityService.User

trait DefaultEnv extends Env {
  type I = User
  type A = SessionAuthenticator
}