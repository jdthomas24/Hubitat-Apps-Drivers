> Source: gopher.ny (Hubitat), shared Oct 30, 2026. Work in progress, kept here as a reference for our apps. Not a requirement.

# Hubitat integration app UI style guide for external developers

This guide describes layout, styling, and navigation practices for third-party Hubitat integration apps. It provides self-contained guidance for designing and verifying your integration's configuration pages.

The measurements below are starting points based on the Hubitat UI in October 2026. Inspect your app on a current hub before applying them. Native markup, theme rules, and font sizes can change. Match the visual relationships and behavior, then measure the rendered result.

Use your hub's web interface for inspection and testing. Preserve the platform's page framing and adapt the examples to your integration's content.

## 1. Preserve the native page

Keep the platform sidebar, breadcrumbs, app title, toolbar, and footer actions. The hub already displays the app name on every configuration page. Do not repeat it in app content.

Start with useful content. Omit page headings such as "Advanced" or "Setup instructions" when they only repeat the navigation label. Keep section headings when they distinguish meaningful groups, such as "Replace a device" or "Setup & help".

No app heading may exceed the rendered Hubitat wordmark. The baseline uses compact `1.143rem` section headings at weight `600`, with no extra heading margin. The desktop wordmark was approximately `1.429rem` when measured.

Avoid filler introductory text, empty sections, decorative cards, and nested borders around ordinary instructions. Explanations should clarify a requirement, choice, consequence, or recovery step.

## 2. Overview structure

Use this order where the integration needs it:

1. A full-width status summary with the current condition and relevant next step.
2. A main column for configured devices or an empty state, followed by discovery and manual-add navigation.
3. A narrower "Setup & help" column for settings and instructions.

Use roughly `62% / 38%` columns. Allow for the gutter in the main column width so the grid does not overflow. For example:

```css
grid-template-columns: minmax(0, calc(62% - 0.571rem)) minmax(0, 38%);
column-gap: 0.571rem;
```

Place a theme-aware vertical divider before the help column, with `0.571rem` padding on its content side. Keep status and footer content full width.

At `max-width: 62.5rem`, stack the main column above help, and remove the divider and its padding. This media query corresponds to approximately 1000 CSS pixels with a 16-pixel browser initial font size.

Discovery lists, pairing forms, and connection settings belong on separate native pages. Do not turn the overview into a long combined setup form.

## 3. Spacing and alignment

The layout should look compact and deliberate. Account for native section, grid, field, and control spacing together; each wrapper must not add another full gap.

| Element | Baseline |
| --- | --- |
| Section bottom margin | `0.429rem` |
| Grid top and bottom padding | `0.286rem` |
| Ordinary field top and bottom margin | `0.286rem` |
| Overview tile top and bottom margin | `0.571rem` each |
| Resulting gap between adjacent overview tiles | About `1.143rem` |
| Column gutter and padding beside divider | `0.571rem` each |
| Status message padding | `0.75rem` |
| Help introduction space below platform title | About `1rem` |

Align these edges explicitly:

- The first tiles in the main and help columns start and finish at the same vertical positions when their content permits.
- Successive tile gaps match in both columns.
- Dropdowns, explanatory text, action buttons, status tiles, and help links on a form share the same left and right content boundaries.
- A refresh button begins at the left edge of the status tiles above it.
- Text above a dropdown begins at the dropdown's left edge, including inside expandable sections.

Native dropdown wrappers may have extra right padding or different width calculations. Correct the wrapper, rather than adding compensating padding to every neighboring element.

Apply any horizontal inset correction once. Depending on the native wrapper, a correction of approximately `-0.5rem` to `-0.571rem` may align app content with the page framing. Use matching positive footer and field insets where needed. Inspect the rendered page before choosing a value, and do not apply the correction again to a nested wrapper.

### Match tile heights without clipping

The main empty-state tile should match the neighboring Setup instructions tile. Help tiles should also match the action links they sit alongside when their content has the same number of lines.

First reduce excess top and bottom padding. Use the same line-height and box model for corresponding rows. Where a minimum is needed, use `min-height`, never a fixed height that clips wrapped text.

A starting point for an empty-state tile is `min-height: 4.474rem`, `box-sizing: border-box`, centered content, and `0.571rem` vertical padding. Corresponding overview help tiles can use `0.714rem` vertical padding and `1.333` line-height. Treat these as starting measurements; recheck with the actual text and current root font size.

Allow longer content to increase height at narrow widths. Do not truncate useful guidance just to preserve equal rows.

## 4. Control styles

### Action navigation tiles

Use raised, theme-aware gray native navigation controls for actions such as Discover devices or Add manually.

| Property | Baseline |
| --- | --- |
| Title | `1.143rem`, weight `400`, theme link color |
| Description | `1rem`, weight `400`, theme secondary text color |
| Line-height | `1.333` |
| Padding | `0.714rem` top/bottom, `1.143rem` left, `2rem` right |
| Corner radius | `0.143rem` |
| Icons | Action icon beside title; chevron centered on right |

The description starts at the control's left text inset, rather than aligning after the title icon. Preserve the native raised surface and interactive states.

### Setup and help tiles

Use an outlined control without a raised shadow. Keep the native theme-aware background. All title and description text uses regular weight `400`, including in Setup & help.

| Property | Baseline |
| --- | --- |
| Title | `1.143rem`, weight `400` |
| Description | `1rem`, weight `400` |
| Border | `0.071rem`, theme surface-border color |
| Corner radius | `0.286rem` |
| Line-height | `1.4` |
| Padding | `0.571rem` top, `2rem` right, `0.964rem` bottom, `3.286rem` left |
| Leading icon | `1.429rem`, inset `1rem`, vertically centered |

For matched overview rows, use the compact padding and line-height described above. If an inset outline replaces a border to avoid adding height, preserve a clear keyboard focus outline separately.

Keep one Setup instructions link when several topics share the same destination. Separate help links are appropriate only for distinct destinations.

### Ordinary action buttons

Refresh discovery, Refresh connection status, and Create/check selected devices are ordinary native buttons with a relevant icon and concise text. Do not make them oversized navigation cards or unstyled text links.

Use native theme classes and icon conventions already present on the page. Keep text readable and retain hover, focus, and disabled states. Align buttons with the form or status content they act on.

### Device links

On an existing device's management page, use its name as the link to the platform device page. Open it in a new tab, add a Font Awesome external-link icon, and use `rel="noopener"` with `target="_blank"`.

Avoid a duplicate device name at the top and a separate "Open device" link. Do not label an existing device "Device added" merely because its management page was opened.

## 5. Status, empty states, and feedback

Distinguish a condition that needs attention from ordinary information.

| Situation | Treatment |
| --- | --- |
| Setup required or connection failure | Compact native warning treatment with icon, explanation, and relevant next step |
| No configured devices | Neutral outlined tile, semibold primary message, smaller supporting text |
| Routine limitation or expected disconnect | Neutral outlined note in the primary text color |
| Action progress or success | Flat neutral feedback |
| Action failure | Standard theme warning colors |

Do not use an info-colored background for routine notes. Preserve clear distinctions in both light and dark themes.

Put feedback directly below the action that produced it. For example, a device update message belongs immediately below a Check and add devices button, ahead of secondary setup/help navigation. A feedback region may show progress, success, or failure; its visibility alone does not mean an error occurred.

Keep meaningful playback or connection readings visible without redundant headers. For example, an Audio playback status may remain as content without an extra "Audio playback" title.

Make errors describe the failed stage. A missing driver must not tell users to change network permissions. Discovery success does not prove that device creation succeeded, and a creation failure does not prove that discovery or the address failed. Include recovery instructions only when they apply to the actual failure.

## 6. Setup, help, and advanced forms

Help pages begin with useful requirements or instructions, with about `1rem` below the platform title. Use short paragraphs and steps. Do not wrap plain Local network access instructions in an additional padded, bordered card.

Explain where settings are changed. When configuration happens on an external device, identify the device and how to reach its settings. For example, television setup instructions should direct users to the physical remote and the menus on the television. Include known menu variants together, and say which normal option to choose. Do not recommend broader access when the normal enabled setting is sufficient.

Keep View app logs near a relevant overview status; do not add it to the help page.

Keep advanced operations separate from ordinary setup. Omit generic repair sections without a distinct, useful recovery action. Device replacement guidance, selectors, and action buttons must share the same content edges.

Expandable sections such as Replace a device and Additional audio pairing must remain open after dropdown changes or form submissions refresh the page. Preserve their open state until the user closes them. Refreshing the form must not silently discard the user's context.

## 7. Navigation is part of consistency

- Register discovery, manual entry, help, connection, and management pages as native pages and navigate using `href` controls.
- Page methods accept an optional parameters `Map`, including an empty map.
- Do not add a Back button at the top of subpages. Native Done returns one level; only overview Done closes the app.
- Include Cancel where it cancels a draft flow. Restore the immediately preceding page and its values, including nested discovery-to-manual flows. Do not add a Cancel button to a read-only device status page without a draft to cancel.
- Style navigation-aware Cancel as a content-sized gray action button: inherited font, regular `1rem` text, small close icon, no chevron, description, or line break.
- Preserve the actual navigation origin across form refreshes. Native Done may carry old parameters; do not let those overwrite the current return path.
- Opening or rendering a page must not create devices or save connections. Creation, replacement, and removal require explicit actions.
- Keep encryption keys in password settings. Do not put them in public state or navigation parameters; clear temporary drafts when leaving the flow.

## 8. CSS and responsive behavior

Prefer native controls, theme classes, and PrimeFlex utilities before writing overrides. Scope overrides to the integration's form using an app-specific marker or class. Do not change every app's native controls through a global selector.

Use theme variables such as `--surface-border`, `--text-color`, `--text-color-secondary`, and `--hubitat-link-color` instead of fixed light-theme colors.

Use `rem` for app-authored sizing and spacing, rounded to at most three decimal places. Keep percentages for fluid widths, unitless line-heights, and zero without a unit. The measurements here used an approximately 14-pixel document root. Measure the current root before converting.

Media-query `rem` units use the browser's initial font size, not the app's document root. Do not convert the layout breakpoint using the 14-pixel app root.

Use `minmax(0, ...)` for grid columns and `min-width: 0` on content wrappers. Allow long names and addresses to wrap. Stack banner text and actions when needed; a further `37.5rem` media query can be useful for stacking a status summary.

Never hide focus indicators to make controls look cleaner. Keep keyboard activation, labels, disabled states, and readable contrast when adapting native markup.

## 9. Review and verification checklist

For a requested mockup, start from a current local-hub screenshot and preserve its shell. Clearly distinguish proposed content and sample data. Prepare high-resolution review screens before implementation when requested.

Before considering an implementation complete:

- Compare the live page against this guide and any agreed design mockups at desktop and narrow widths. Inspect actual screenshots, not only source CSS.
- Check both empty and populated overviews, long device names, multiline help text, and warning/progress/success feedback where feasible.
- Confirm matched tile edges, equal gaps, aligned form boundaries, compact headings, and visible actions.
- Check the column breakpoint, wrapping, horizontal overflow, divider removal, and keyboard focus. Check both themes where feasible.
- Verify separate-page navigation, nested Done behavior, Cancel draft restoration, and expandable sections surviving submissions.
- Verify changed Groovy source with the checks appropriate to your development environment, in addition to testing the rendered UI.
- Install or update the app on your test hub using your normal development workflow, then verify the installed UI in a browser. Record any verification that could not be completed.

Document reusable design decisions and their reasons in your project. Keep app-specific workarounds distinct from shared layout and interaction rules.
