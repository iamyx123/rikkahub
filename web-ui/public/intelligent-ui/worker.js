/* Only the bundled declarative parser runs here. Model text is never evaluated as JS. */
importScripts("engine.js");
self.onmessage = ({ data }) => {
  self.postMessage(JSON.parse(RikkaOpenUI.renderJson(JSON.stringify(data))));
};
