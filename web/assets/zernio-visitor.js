(() => {
  'use strict';
  // Visitor-only integration. Never store a key or URL in localStorage, cookies or app state.
  const platforms = new Map([
    ['linkedin.com', 'LinkedIn'], ['instagram.com', 'Instagram'],
    ['facebook.com', 'Facebook'], ['x.com', 'X'], ['twitter.com', 'X'],
    ['tiktok.com', 'TikTok'], ['youtube.com', 'YouTube'],
    ['threads.net', 'Threads'], ['pinterest.com', 'Pinterest']
  ]);
  const output = (id, message) => {
    const el = document.getElementById(id);
    if (el) el.textContent = message;
    return el;
  };
  // An example may be inspected without any login, key or network call.
  document.addEventListener('click', event => {
    if (event.target.id !== 'zernio-sample-btn') return;
    const input = document.getElementById('zernio-profile-url');
    if (input && input.form) {
      input.value = 'https://www.linkedin.com/in/example';
      input.form.requestSubmit();
    }
  });
  document.addEventListener('submit', async event => {
    if (event.target.id === 'zernio-link-preview') {
      event.preventDefault();
      const input = event.target.querySelector('input');
      const value = input.value.trim();
      input.value = '';
      try {
        const url = new URL(value);
        const host = url.hostname.toLowerCase().replace(/^www\./, '');
        const platform = platforms.get(host);
        if (url.protocol !== 'https:' || !platform || !url.pathname || url.pathname === '/' || url.username || url.password) {
          throw new Error('Use a complete HTTPS profile link from a supported platform.');
        }
        output('zernio-link-result', `${platform} link format recognized: ${url.origin}${url.pathname}. This is a URL-only preview; identity and account ownership are NOT verified. No information was uploaded or saved.`);
      } catch (error) {
        output('zernio-link-result', error.message || 'Invalid profile URL.');
      }
      return;
    }
    if (event.target.id !== 'zernio-key-check') return;
    event.preventDefault();
    const input = event.target.querySelector('input[type=password]');
    const key = input.value.trim();
    input.value = ''; // Clear credential from visible DOM immediately.
    const result = output('zernio-key-result', 'Checking your own connected accounts (read-only)...');
    if (!key || key.length > 512) {
      output('zernio-key-result', 'Enter a restricted read-only Zernio API key.');
      return;
    }
    try {
      const response = await fetch('/api/zernio/accounts', {
        method: 'GET',
        headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
        credentials: 'omit', cache: 'no-store', redirect: 'error'
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.error || `Zernio check failed (HTTP ${response.status}).`);
      const accounts = Array.isArray(body.connectedAccounts) ? body.connectedAccounts : [];
      result.replaceChildren();
      const heading = document.createElement('p');
      heading.textContent = `Verified Zernio response: ${accounts.length} connected account(s) shown. No account modification or publishing performed.`;
      result.append(heading);
      if (!accounts.length) {
        const p = document.createElement('p');
        p.textContent = 'No social accounts are connected to this key. Connect your account in the Zernio dashboard first.';
        result.append(p);
      }
      for (const account of accounts) {
        const row = document.createElement('p');
        row.textContent = `${account.platform || 'Platform'} — ${account.username || account.displayName || 'Connected account'} (${account.isActive === false ? 'inactive' : 'connected'})`;
        result.append(row);
      }
    } catch (error) {
      output('zernio-key-result', error.message || 'Unable to inspect your account.');
    }
  });
})();
