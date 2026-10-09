// `?night=1` in the address previews the night look during the day (for checking the design).
export const forceNight = () => typeof location !== 'undefined' && /[?&]night=1\b/.test(location.search)
