// Profiles shared by the extension and its preferences window.

export const DEFAULT_PORT = 8765;

/** Profiles stored in GSettings, normalised and with ids. */
export function loadProfiles(settings) {
    let list = [];
    try {
        list = JSON.parse(settings.get_string('profiles'));
    } catch (e) {
        console.warn(`RemotePhone: unreadable profiles, ignoring them: ${e}`);
    }
    if (!Array.isArray(list))
        return [];
    return list.filter(p => p && typeof p === 'object').map(p => ({
        id: String(p.id || newId()),
        name: String(p.name || p.host || 'Phone'),
        host: String(p.host || '').trim(),
        port: Number.isInteger(p.port) && p.port > 0 && p.port < 65536 ? p.port : DEFAULT_PORT,
        audio: !!p.audio,
        fullscreen: !!p.fullscreen,
    }));
}

export function saveProfiles(settings, profiles) {
    settings.set_string('profiles', JSON.stringify(profiles));
}

export function newId() {
    return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/** Client arguments for a profile, appended to the configured command. */
export function profileArgs(profile) {
    const args = ['--name', profile.name, '--port', String(profile.port)];
    if (profile.host)
        args.push('--host', profile.host);
    if (profile.audio)
        args.push('--audio');
    if (profile.fullscreen)
        args.push('--fullscreen');
    return args;
}
