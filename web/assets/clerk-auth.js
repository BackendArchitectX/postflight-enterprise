(() => {
  'use strict';
  // Load Clerk only for a configured private runtime. No API secrets or token storage.
  let loading;
  function script(src, attributes = {}) {
    return new Promise((resolve, reject) => {
      const element = document.createElement('script');
      element.src = src;
      element.async = true;
      element.crossOrigin = 'anonymous';
      Object.entries(attributes).forEach(([name, value]) => element.setAttribute(name, value));
      element.onload = resolve;
      element.onerror = () => reject(new Error('Unable to load Clerk from the configured host'));
      document.head.append(element);
    });
  }
  async function initialize(config) {
    if (!config?.enabled) return null;
    if (loading) return loading;
    loading = (async () => {
      const frontendApi = new URL(config.issuer);
      if (frontendApi.protocol !== 'https:' || frontendApi.pathname !== '/' || !config.publishableKey)
        throw new Error('Invalid Clerk configuration');
      const base = frontendApi.origin;
      // These are the two CDN assets recommended by Clerk for plain JavaScript apps.
      await script(`${base}/npm/@clerk/ui@1/dist/ui.browser.js`);
      await script(`${base}/npm/@clerk/clerk-js@6/dist/clerk.browser.js`, {
        'data-clerk-publishable-key': config.publishableKey
      });
      if (!window.Clerk || !window.__internal_ClerkUICtor)
        throw new Error('Clerk browser SDK was not initialized');
      await window.Clerk.load({ ui: { ClerkUI: window.__internal_ClerkUICtor } });
      return window.Clerk;
    })();
    try { return await loading; }
    catch (error) { loading = undefined; throw error; }
  }
  window.PostFlightClerk = Object.freeze({ initialize });
})();
