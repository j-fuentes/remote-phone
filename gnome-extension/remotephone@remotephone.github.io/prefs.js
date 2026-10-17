import Adw from 'gi://Adw';
import Gtk from 'gi://Gtk';

import {ExtensionPreferences, gettext as _} from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';

import {DEFAULT_PORT, loadProfiles, newId, saveProfiles} from './profiles.js';

export default class RemotePhonePreferences extends ExtensionPreferences {
    fillPreferencesWindow(window) {
        const settings = this.getSettings();
        window._settings = settings; // keep it alive with the window
        window.set_default_size(560, 640);

        const page = new Adw.PreferencesPage({title: _('Profiles'), icon_name: 'phone-symbolic'});
        window.add(page);

        // Client command
        const clientGroup = new Adw.PreferencesGroup({
            title: _('Client'),
            description: _('Command that starts the RemotePhone desktop client. Profile options (--host, --port, --name, --audio, --fullscreen) are appended.'),
        });
        const command = new Adw.EntryRow({title: _('Command'), text: settings.get_string('client-command')});
        command.connect('changed', () => settings.set_string('client-command', command.text.trim() || 'remotephone'));
        clientGroup.add(command);
        page.add(clientGroup);

        // Profiles
        const addButton = new Gtk.Button({icon_name: 'list-add-symbolic', valign: Gtk.Align.CENTER, tooltip_text: _('Add profile')});
        addButton.add_css_class('flat');
        let group = null;

        const render = (expandId = null) => {
            if (group)
                page.remove(group);
            group = new Adw.PreferencesGroup({
                title: _('Phones'),
                description: _('Each profile appears as a switch in the top bar menu.'),
                header_suffix: addButton,
            });
            page.add(group);

            const profiles = loadProfiles(settings);
            const save = () => saveProfiles(settings, profiles);

            if (profiles.length === 0) {
                group.add(new Adw.ActionRow({title: _('No profiles yet'), subtitle: _('Add one with the + button.')}));
            }
            for (const profile of profiles) {
                const row = new Adw.ExpanderRow({
                    title: profile.name,
                    subtitle: profile.host ? `${profile.host}:${profile.port}` : _('No address: scans the network'),
                    expanded: profile.id === expandId,
                });
                const refresh = () => {
                    row.title = profile.name || _('Unnamed');
                    row.subtitle = profile.host ? `${profile.host}:${profile.port}` : _('No address: scans the network');
                };

                const name = new Adw.EntryRow({title: _('Name'), text: profile.name});
                name.connect('changed', () => { profile.name = name.text.trim(); refresh(); save(); });
                row.add_row(name);

                const host = new Adw.EntryRow({title: _('Phone IP or host name'), text: profile.host});
                host.connect('changed', () => { profile.host = host.text.trim(); refresh(); save(); });
                row.add_row(host);

                const port = new Adw.SpinRow({
                    title: _('Port'),
                    adjustment: new Gtk.Adjustment({lower: 1, upper: 65535, step_increment: 1, value: profile.port}),
                });
                port.connect('notify::value', () => { profile.port = Math.round(port.value); refresh(); save(); });
                row.add_row(port);

                const audio = new Adw.SwitchRow({title: _('Stream audio'), active: profile.audio});
                audio.connect('notify::active', () => { profile.audio = audio.active; save(); });
                row.add_row(audio);

                const fullscreen = new Adw.SwitchRow({title: _('Start fullscreen'), active: profile.fullscreen});
                fullscreen.connect('notify::active', () => { profile.fullscreen = fullscreen.active; save(); });
                row.add_row(fullscreen);

                const remove = new Gtk.Button({icon_name: 'user-trash-symbolic', valign: Gtk.Align.CENTER, tooltip_text: _('Delete profile')});
                remove.add_css_class('flat');
                remove.connect('clicked', () => {
                    saveProfiles(settings, profiles.filter(p => p.id !== profile.id));
                    render();
                });
                row.add_suffix(remove);

                group.add(row);
            }
        };

        addButton.connect('clicked', () => {
            const profiles = loadProfiles(settings);
            const id = newId();
            profiles.push({id, name: _('Phone %d').format(profiles.length + 1), host: '', port: DEFAULT_PORT, audio: false, fullscreen: false});
            saveProfiles(settings, profiles);
            render(id);
        });

        render();
    }
}
