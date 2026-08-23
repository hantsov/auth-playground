/*
 * Smart-ID notification flow, browser side.
 *
 * Scope, deliberately narrow: collect two fields, start a session, show the
 * verification code, poll until something happens. Every decision that matters
 * is made on the server.
 *
 * In particular this file NEVER talks to SK. It polls our own /login/smart-id
 * endpoints. Letting the browser poll sid.demo.sk.ee directly would be shorter
 * and would move the trust decision into JavaScript, where the signature,
 * certificate chain, assurance level and challenge checks are all either absent
 * or unenforceable. Anything a browser concludes about a Smart-ID response is a
 * claim, not a verification.
 */
(function () {
  'use strict';

  const form = document.getElementById('smart-id-form');
  if (!form) {
    return;
  }

  const waiting = document.getElementById('smart-id-waiting');
  const codeEl = document.getElementById('smart-id-code');
  const statusEl = document.getElementById('smart-id-waiting-status');
  const errorEl = document.getElementById('smart-id-error');
  const submitButton = document.getElementById('smart-id-submit');
  const cancelButton = document.getElementById('smart-id-cancel');

  /*
   * Set while a login is in flight so Cancel can stop the loop. The session
   * itself keeps running at SK until the user acts or it times out — cancelling
   * here only stops us waiting for it.
   */
  let polling = false;

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    showWaiting();

    try {
      const session = await startSession();
      codeEl.textContent = session.verificationCode;
      await pollUntilResolved(session.sessionId);
    } catch (error) {
      showError(error.message);
    }
  });

  cancelButton.addEventListener('click', function () {
    polling = false;
    showForm();
  });

  async function startSession() {
    const response = await fetch('/login/smart-id/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        country: document.getElementById('smart-id-country').value,
        nationalId: document.getElementById('smart-id-national').value.trim()
      })
    });

    if (!response.ok) {
      throw new Error(await messageFrom(response));
    }
    return response.json();
  }

  /*
   * The status endpoint is a LONG poll: it does not return until the user acts
   * or the server-side timeout expires. So there is no setInterval and no sleep
   * here — a RUNNING response means "ask again", immediately. Adding a delay
   * would stack our wait on top of a wait that already happened.
   */
  async function pollUntilResolved(sessionId) {
    polling = true;

    while (polling) {
      const response = await fetch('/login/smart-id/status?sessionId=' + encodeURIComponent(sessionId));

      if (!response.ok) {
        throw new Error(await messageFrom(response));
      }

      const status = await response.json();

      if (status.state === 'AUTHENTICATED') {
        polling = false;
        /*
         * Back to the authorization request the user arrived with. The server
         * decided this URL from the saved request; the page is only following
         * it, which keeps the redirect target out of reach of anything typed
         * into the form.
         */
        window.location.assign(status.redirectUrl);
        return;
      }

      if (status.state === 'FAILED') {
        polling = false;
        showError(status.message);
        return;
      }

      // RUNNING: the person has not answered yet.
      statusEl.textContent = 'Waiting for confirmation…';
    }
  }

  /* Prefers the server's message; falls back to something honest but vague. */
  async function messageFrom(response) {
    try {
      const body = await response.json();
      if (body && body.message) {
        return body.message;
      }
    } catch (ignored) {
      // Not JSON — a proxy error page, most likely.
    }
    return 'Smart-ID sign-in could not be completed. Please try again.';
  }

  function showWaiting() {
    errorEl.hidden = true;
    form.hidden = true;
    waiting.hidden = false;
    submitButton.disabled = true;
    codeEl.textContent = '----';
    statusEl.textContent = 'Sending request to your Smart-ID app…';
  }

  function showForm() {
    waiting.hidden = true;
    form.hidden = false;
    submitButton.disabled = false;
  }

  function showError(message) {
    polling = false;
    showForm();
    errorEl.textContent = message;
    errorEl.hidden = false;
  }
})();
