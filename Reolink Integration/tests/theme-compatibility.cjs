// From this directory: npm install, then npm test.
// Exercise selector gating against DOM fixtures, without a browser or a running hub.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const postcss = require('postcss');
const { parseDocument } = require('htmlparser2');
const { selectAll } = require('css-select');
const source = fs.readFileSync(path.join(__dirname, '..', 'ParentApp.groovy'), 'utf8');
const css = source.match(/private String legacyLightThemeCss\(\) \{\s*"""([\s\S]*?)"""/)[1];
const rules = postcss.parse(css).nodes.filter(n => n.type === 'rule');
const markup = `<div id="formApp"><button class="mdl-button app-ui-button app-ui-button-primary">Add source</button><button disabled class="mdl-button app-ui-button app-ui-button-primary">Disabled</button><div class="app-ui-navigation"><button data-test-hover class="hrefElem"><span class="app-ui-navigation-selected">Selected</span></button></div><span class="hub-text-success">Online</span></div><div class="reolink-dialog"><span class="hub-text-success">Current</span></div><div id="unrelated"><button class="mdl-button app-ui-button app-ui-button-primary">Other app</button></div>`;
const options = {pseudos: {enabled: el => !('disabled' in el.attribs), disabled: el => 'disabled' in el.attribs, hover: () => false, active: () => false, 'focus-visible': () => false}};
function declarations(root, target) {
    const result = {};
    for (const rule of rules) {
        if (selectAll(rule.selector.replace(/::before/g, '').replace(/:focus-visible/g, '[data-test-focus]').replace(/:hover/g, '[data-test-hover]'), root, options).includes(target)) {
            rule.walkDecls(d => { result[d.prop] = d.value; });
        }
    }
    return result;
}
for (const [mode, capable] of [[null, false], ['light', false], ['dark', false], ['light', true], ['dark', true]]) {
    const root = parseDocument(`<html${mode ? ` data-hubitat-theme="${mode}"` : ''}><body>${capable ? '<div data-hubitat-app-ui-semantics="1"></div>' : ''}${markup}</body></html>`);
    if (capable) {
        for (const rule of rules) assert.equal(selectAll(rule.selector.replace(/::before/g, '').replace(/:focus-visible/g, '[data-test-focus]').replace(/:hover/g, '[data-test-hover]'), root, options).length, 0, `${mode}: legacy rule leaked`);
    } else {
        const primary = selectAll('#formApp button.app-ui-button-primary', root);
        assert.equal(declarations(root, primary[0]).background, 'var(--hubitat-brand-background, #81bc00)');
        assert.equal(declarations(root, primary[0]).color, 'var(--hubitat-on-brand, #fff)');
        assert.equal(declarations(root, primary[1]).background, 'var(--hubitat-control-background, #eee)');
        assert.equal(declarations(root, selectAll('.app-ui-navigation .hrefElem', root)[0]).background, 'var(--hubitat-selection-background, #eaf2fc)');
        assert.equal(declarations(root, selectAll('.reolink-dialog .hub-text-success', root)[0]).color, 'var(--hubitat-success-color, #356700)');
        assert.deepEqual(declarations(root, selectAll('#unrelated button', root)[0]), {});
    }
}
// App-owned variable references must remain valid when a hub token is missing.
for (const match of source.matchAll(/var\(--[\w-]+([^)]*)\)/g)) assert(match[1].trim().startsWith(','), `Missing fallback: ${match[0]}`);
assert(css.includes(':not(:has(.app-ui-navigation-selected)):hover'));
assert(source.includes('legacyLightThemeCss() + "#formApp'));
console.log('PASS: legacy and early-beta fallbacks; capable beta light/dark exclusion; selected hover; disabled actions; dialog and app isolation; variable fallbacks.');
