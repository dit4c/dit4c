describe "session", () ->

  Session = null

  before (done) ->
    requirejs ['angular-mocks', './services/session'], () ->
      done()

  beforeEach (done) ->
    module('app.services')
    inject (_Session_) ->
      Session = _Session_
      done()

  it 'should manage a user', (done) ->
    expect(Session.user).to.not.be.undefined
    done()

  it 'should accept a user to login', (done) ->
    expect(Session.user).to.be.null
    Session.login
      id: '0123456789abcdef'
      name: 'Tommy Atkins'
    expect(Session.user).to.be.an('object')
    expect(Session.user.id).to.equal('0123456789abcdef')
    expect(Session.user.name).to.equal('Tommy Atkins')
    done()

  it 'should accept a user to logout', (done) ->
    Session.login
      id: '0123456789abcdef'
      name: 'Tommy Atkins'
    expect(Session.user).to.be.an('object')
    expect(Session.user.id).to.equal('0123456789abcdef')
    expect(Session.user.name).to.equal('Tommy Atkins')
    Session.logout()
    expect(Session.user).to.be.null
    done()