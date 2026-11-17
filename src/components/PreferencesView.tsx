import { Monitor, Moon, RotateCcw, Sun } from 'lucide-react';
import { motion } from 'motion/react';
import type {
  AppPreferences,
  DensityMode,
  GlassIntensity,
  ThemePreference,
} from '../types/app';

interface PreferencesViewProps {
  themePreference: ThemePreference;
  onChangeTheme: (theme: ThemePreference) => void;
  preferences: AppPreferences;
  onChangePreferences: (next: AppPreferences) => void;
  onResetDefaults: () => void;
}

export function PreferencesView({
  themePreference,
  onChangeTheme,
  preferences,
  onChangePreferences,
  onResetDefaults,
}: PreferencesViewProps) {
  const tapMotion = preferences.reducedMotion ? undefined : { scale: 0.98 };

  const themeOptions: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
    { value: 'light', label: 'Light', icon: Sun },
    { value: 'dark', label: 'Dark', icon: Moon },
    { value: 'system', label: 'System', icon: Monitor },
  ];

  const glassOptions: { value: GlassIntensity; label: string }[] = [
    { value: 'subtle', label: 'Subtle' },
    { value: 'balanced', label: 'Balanced' },
    { value: 'frosted', label: 'Frosted' },
  ];

  const densityOptions: { value: DensityMode; label: string }[] = [
    { value: 'comfortable', label: 'Comfortable' },
    { value: 'compact', label: 'Compact' },
  ];

  return (
    <div className="space-y-8 max-w-3xl">
      <div>
        <h1 className="font-display text-2xl sm:text-3xl font-bold tracking-tight text-[#103c60] dark:text-[#f1f5f9]">
          Preferences
        </h1>
        <p className="mt-1 text-sm text-[#475569] dark:text-[#94a3b8]">
          Adjust surface theme, glassmorphism blur, density, and motion.
        </p>
      </div>

      <section className="emofah-glass rounded-3xl divide-y divide-[#103c60]/[0.08] dark:divide-[#43b1d4]/[0.12]">
        <div className="p-5 sm:p-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h2 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              Appearance Mode
            </h2>
            <p className="mt-0.5 text-xs text-[#64748b] dark:text-[#94a3b8]">
              Sync with device theme or lock Light or Dark surfaces.
            </p>
          </div>

          <div className="inline-flex items-center p-1 rounded-xl bg-[#103c60]/[0.06] dark:bg-[#43b1d4]/[0.09] border border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14] self-start sm:self-auto">
            {themeOptions.map(({ value, label, icon: Icon }) => {
              const active = themePreference === value;
              return (
                <motion.button
                  key={value}
                  type="button"
                  whileTap={tapMotion}
                  onClick={() => onChangeTheme(value)}
                  className={`relative min-h-[38px] px-3.5 py-1.5 rounded-lg text-xs font-medium inline-flex items-center gap-1.5 transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
                    active
                      ? 'text-[#103c60] dark:text-[#f1f5f9]'
                      : 'text-[#64748b] dark:text-[#94a3b8] hover:text-[#103c60] dark:hover:text-[#f1f5f9]'
                  }`}
                >
                  {active && (
                    <motion.span
                      layoutId="settings-theme-pill"
                      transition={
                        preferences.reducedMotion
                          ? { duration: 0 }
                          : { type: 'spring', stiffness: 440, damping: 34 }
                      }
                      className="absolute inset-0 rounded-lg bg-white dark:bg-[#132945] shadow-xs"
                    />
                  )}
                  <Icon className="w-3.5 h-3.5 relative z-10" />
                  <span className="relative z-10">{label}</span>
                </motion.button>
              );
            })}
          </div>
        </div>

        <div className="p-5 sm:p-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h2 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              Glassmorphism Depth
            </h2>
            <p className="mt-0.5 text-xs text-[#64748b] dark:text-[#94a3b8]">
              Control backdrop blur strength across panels and navigation bars.
            </p>
          </div>

          <div className="inline-flex items-center p-1 rounded-xl bg-[#103c60]/[0.06] dark:bg-[#43b1d4]/[0.09] border border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14] self-start sm:self-auto">
            {glassOptions.map(({ value, label }) => {
              const active = preferences.glassIntensity === value;
              return (
                <button
                  key={value}
                  type="button"
                  onClick={() =>
                    onChangePreferences({ ...preferences, glassIntensity: value })
                  }
                  className={`relative min-h-[38px] px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
                    active
                      ? 'text-[#103c60] dark:text-[#f1f5f9]'
                      : 'text-[#64748b] dark:text-[#94a3b8] hover:text-[#103c60] dark:hover:text-[#f1f5f9]'
                  }`}
                >
                  {active && (
                    <motion.span
                      layoutId="settings-glass-pill"
                      transition={
                        preferences.reducedMotion
                          ? { duration: 0 }
                          : { type: 'spring', stiffness: 440, damping: 34 }
                      }
                      className="absolute inset-0 rounded-lg bg-white dark:bg-[#132945] shadow-xs"
                    />
                  )}
                  <span className="relative z-10">{label}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="p-5 sm:p-6 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h2 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              List Density
            </h2>
            <p className="mt-0.5 text-xs text-[#64748b] dark:text-[#94a3b8]">
              Switch between generous touch spacing or compact rows.
            </p>
          </div>

          <div className="inline-flex items-center p-1 rounded-xl bg-[#103c60]/[0.06] dark:bg-[#43b1d4]/[0.09] border border-[#103c60]/[0.08] dark:border-[#43b1d4]/[0.14] self-start sm:self-auto">
            {densityOptions.map(({ value, label }) => {
              const active = preferences.density === value;
              return (
                <button
                  key={value}
                  type="button"
                  onClick={() =>
                    onChangePreferences({ ...preferences, density: value })
                  }
                  className={`relative min-h-[38px] px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer whitespace-nowrap shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
                    active
                      ? 'text-[#103c60] dark:text-[#f1f5f9]'
                      : 'text-[#64748b] dark:text-[#94a3b8] hover:text-[#103c60] dark:hover:text-[#f1f5f9]'
                  }`}
                >
                  {active && (
                    <motion.span
                      layoutId="settings-density-pill"
                      transition={
                        preferences.reducedMotion
                          ? { duration: 0 }
                          : { type: 'spring', stiffness: 440, damping: 34 }
                      }
                      className="absolute inset-0 rounded-lg bg-white dark:bg-[#132945] shadow-xs"
                    />
                  )}
                  <span className="relative z-10">{label}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="p-5 sm:p-6 flex items-center justify-between gap-4">
          <div>
            <h2 className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              Reduce Motion
            </h2>
            <p className="mt-0.5 text-xs text-[#64748b] dark:text-[#94a3b8]">
              Minimize spring and slide transitions across views.
            </p>
          </div>

          <button
            type="button"
            role="switch"
            aria-checked={preferences.reducedMotion}
            onClick={() =>
              onChangePreferences({
                ...preferences,
                reducedMotion: !preferences.reducedMotion,
              })
            }
            className={`relative inline-flex h-8 w-14 shrink-0 items-center rounded-full p-1 transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4] ${
              preferences.reducedMotion
                ? 'bg-[#14558a] dark:bg-[#43b1d4]'
                : 'bg-[#103c60]/18 dark:bg-[#43b1d4]/25'
            }`}
          >
            <span className="sr-only">Toggle reduce motion</span>
            <motion.span
              layout
              transition={
                preferences.reducedMotion
                  ? { duration: 0 }
                  : { type: 'spring', stiffness: 500, damping: 34 }
              }
              className={`h-6 w-6 rounded-full bg-white shadow-sm ${
                preferences.reducedMotion ? 'translate-x-6' : 'translate-x-0'
              }`}
            />
          </button>
        </div>
      </section>

      <div className="flex justify-end">
        <motion.button
          type="button"
          whileTap={tapMotion}
          onClick={onResetDefaults}
          className="min-h-[44px] px-4 py-2 rounded-xl border border-[#103c60]/12 dark:border-[#43b1d4]/20 bg-white/60 dark:bg-[#0d1f36]/60 hover:bg-white dark:hover:bg-[#122a48] text-xs font-medium text-[#475569] dark:text-[#cbd5e1] inline-flex items-center gap-2 transition-colors cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
        >
          <RotateCcw className="w-3.5 h-3.5" />
          <span>Reset Preferences</span>
        </motion.button>
      </div>
    </div>
  );
}
