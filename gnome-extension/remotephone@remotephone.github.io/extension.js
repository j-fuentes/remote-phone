import GObject from 'gi://GObject';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import St from 'gi://St';

import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {Extension, gettext as _} from 'resource:///org/gnome/shell/extensions/extension.js';

import {loadProfiles, profileArgs} from './profiles.js';

/**
 * Top bar menu with one switch per connection profile. Switching a profile on starts the
 * RemotePhone desktop client connected to that phone; switching it off (or closing the
 * client window) stops it.
 */
const RemotePhoneIndicator = GObject.registerClass(
class RemotePhoneIndicator extends PanelMenu.Button {
    _init(extension) {
        super._init(0.0, 'RemotePhone');
        this._extension = extension;
        this._settings = extension.getSettings();
        this._running = new Map(); // profile id -> Gio.Subprocess

        this._icon = new St.Icon({
            gicon: Gio.icon_new_for_string(`${extension.path}/icons/remotephone-symbolic.svg`),
            style_class: 'system-status-icon',
        });
        this.add_child(this._icon);

        this._rebuildId = 0;
        this._settingsChangedId = this._settings.connect('changed', () => this._queueRebuild());
        // Rebuild on open so the switches reflect clients closed from their own window
        this.menu.connect('open-state-changed', (_menu, open) => {
            if (open)
                this._rebuild();
        });
        this._rebuild();
    }

    _rebuild() {
        this.menu.removeAll();
        const profiles = loadProfiles(this._settings);

        if (profiles.length === 0) {
            const empty = new PopupMenu.PopupMenuItem(_('No profiles yet'), {reactive: false});
            this.menu.addMenuItem(empty);
        }
        for (const profile of profiles) {
            const item = new PopupMenu.PopupSwitchMenuItem(profile.name, this._running.has(profile.id));
            const detail = profile.host ? `${profile.host}:${profile.port}` : _('scan the network');
            item.label.text = `${profile.name}  ·  ${detail}`;
            item.connect('toggled', (_item, on) => {
                if (on)
                    this._launch(profile);
                else
                    this._stop(profile.id);
            });
            this.menu.addMenuItem(item);
        }

        this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        const scan = new PopupMenu.PopupMenuItem(_('Open client (scan network)'));
        scan.connect('activate', () => this._launch({id: '__scan__', name: '', host: '', port: 8765}));
        this.menu.addMenuItem(scan);
        const prefs = new PopupMenu.PopupMenuItem(_('Profiles…'));
        prefs.connect('activate', () => this._extension.openPreferences());
        this.menu.addMenuItem(prefs);

        this._icon.opacity = this._running.size > 0 ? 255 : 180;
    }

    // Menu items are rebuilt outside their own signal handlers
    _queueRebuild() {
        if (this._rebuildId)
            return;
        this._rebuildId = GLib.idle_add(GLib.PRIORITY_DEFAULT_IDLE, () => {
            this._rebuildId = 0;
            if (this._settings)
                this._rebuild();
            return GLib.SOURCE_REMOVE;
        });
    }

    _launch(profile) {
        if (this._running.has(profile.id))
            return;
        let argv;
        try {
            const [, cmd] = GLib.shell_parse_argv(this._settings.get_string('client-command'));
            argv = profile.id === '__scan__' ? cmd : [...cmd, ...profileArgs(profile)];
        } catch (e) {
            Main.notifyError(_('RemotePhone'), _('Invalid client command: %s').format(e.message));
            this._queueRebuild();
            return;
        }
        let proc;
        try {
            proc = Gio.Subprocess.new(argv, Gio.SubprocessFlags.NONE);
        } catch (e) {
            Main.notifyError(_('RemotePhone'),
                _('Could not start "%s": %s. Install the client (pip install remote-phone) or set its command in Profiles.')
                    .format(argv[0], e.message));
            this._queueRebuild();
            return;
        }
        if (profile.id === '__scan__')
            return; // not tracked: the client finds the phone itself
        this._running.set(profile.id, proc);
        proc.wait_async(null, (p, res) => {
            try {
                p.wait_finish(res);
            } catch (_e) {
            }
            if (this._running.get(profile.id) === p) {
                this._running.delete(profile.id);
                this._queueRebuild();
            }
        });
        this._queueRebuild();
    }

    _stop(id) {
        const proc = this._running.get(id);
        this._running.delete(id);
        proc?.send_signal(15); // SIGTERM: Qt closes the window and the connection
        this._queueRebuild();
    }

    destroy() {
        if (this._rebuildId)
            GLib.source_remove(this._rebuildId);
        this._settings.disconnect(this._settingsChangedId);
        this._settings = null;
        // Clients keep running: the extension is disabled on the lock screen too, and a
        // locked screen should not drop the phone connections.
        this._running.clear();
        super.destroy();
    }
});

export default class RemotePhoneExtension extends Extension {
    enable() {
        this._indicator = new RemotePhoneIndicator(this);
        Main.panel.addToStatusArea(this.uuid, this._indicator);
    }

    disable() {
        this._indicator?.destroy();
        this._indicator = null;
    }
}
