import { Github, KeyRound, ShieldCheck } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type { ApiError } from "../api/client";
import { api } from "../api/endpoints";
import { useApiKey, useMe, useSettings, useUpdateSettings } from "../api/hooks";
import { SEVERITIES, type Settings as SettingsData, type Severity } from "../api/types";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { Button, ErrorState, Field, inputClass, LoadingBlock, PageHeader, Panel } from "../components/ui";
import { dateTime, severityLabel } from "../lib/format";

export function validateSettings(form: { aiModel: string; enabledLanguages: string[]; maxFiles: number }, maxLimit: number) {
  const errors: Record<string, string> = {};
  if (form.aiModel && !/^[A-Za-z0-9._:/@-]{1,100}$/.test(form.aiModel)) errors.aiModel = "Use letters, numbers and . _ : / @ - only.";
  if (form.enabledLanguages.length === 0) errors.enabledLanguages = "Enable at least one language.";
  if (!Number.isInteger(form.maxFiles) || form.maxFiles < 1 || form.maxFiles > maxLimit) errors.maxFiles = `Enter a whole number from 1 to ${maxLimit}.`;
  return errors;
}

function Section({ title, description, children }: { title: string; description?: string; children: React.ReactNode }) {
  return (
    <Panel className="grid gap-6 p-6 md:grid-cols-[16rem_1fr]">
      <div>
        <h2 className="font-semibold">{title}</h2>
        {description && <p className="mt-1 text-sm text-muted">{description}</p>}
      </div>
      <div className="min-w-0">{children}</div>
    </Panel>
  );
}

export default function Settings() {
  const settings = useSettings();
  if (settings.isLoading) return <LoadingBlock rows={8} label="Loading settings" />;
  if (settings.error || !settings.data) return <ErrorState error={settings.error} onRetry={() => settings.refetch()} />;
  return <SettingsForm settings={settings.data} />;
}

function SettingsForm({ settings }: { settings: SettingsData }) {
  const { data: me } = useMe();
  const update = useUpdateSettings();
  const { set: setKey, clear: clearKey } = useApiKey();
  const qc = useQueryClient();
  const navigate = useNavigate();

  const [provider, setProvider] = useState(settings.aiProvider);
  const [model, setModel] = useState(settings.aiModel ?? "");
  const [analyzers, setAnalyzers] = useState<string[]>(settings.enabledAnalyzers);
  const [languages, setLanguages] = useState<string[]>(settings.enabledLanguages);
  const [minSeverity, setMinSeverity] = useState<Severity>(settings.minSeverity);
  const [maxFiles, setMaxFiles] = useState(String(settings.maxFiles));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [newKey, setNewKey] = useState("");
  const [disconnect, setDisconnect] = useState<null | "token" | "all">(null);
  const [disconnecting, setDisconnecting] = useState(false);

  useEffect(() => {
    setModel(settings.aiModel ?? "");
  }, [settings.aiModel]);

  const providerInfo = settings.providers.find((p) => p.id === provider);
  const toggle = (list: string[], set: (v: string[]) => void, id: string) => set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

  function save(e: FormEvent) {
    e.preventDefault();
    const form = { aiModel: model.trim(), enabledLanguages: languages, maxFiles: Number(maxFiles) };
    const v = validateSettings(form, settings.maxFilesLimit);
    setErrors(v);
    if (Object.keys(v).length) return;
    update.mutate(
      { aiProvider: provider, aiModel: form.aiModel || null, enabledAnalyzers: analyzers, enabledLanguages: languages, minSeverity, maxFiles: form.maxFiles },
      { onError: (err) => {
          const apiErr = err as ApiError;
          setErrors(apiErr.fieldErrors ?? {});
          toast.error(apiErr.message);
        } },
    );
  }

  async function doDisconnect() {
    setDisconnecting(true);
    try {
      await api.disconnect(disconnect === "all");
      qc.clear();
      qc.setQueryData(["me"], null);
      toast.success(disconnect === "all" ? "Account data deleted" : "GitHub disconnected");
      navigate("/");
    } catch (err) {
      toast.error((err as ApiError).message);
    } finally {
      setDisconnecting(false);
      setDisconnect(null);
    }
  }

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <PageHeader title="Settings" description="Control how CodeSage reviews your code and which AI provider it uses." />

      <Section title="GitHub connection" description="CodeSage acts with your own GitHub permissions and never more.">
        {me && (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <Github className="size-5 text-sage" aria-hidden />
              <div>
                <p className="font-medium">
                  {me.githubConnected ? "Connected" : "Authorization expired"} as @{me.login}
                </p>
                <p className="text-sm text-muted">
                  Scopes: <span className="font-mono">{me.githubScopes ?? "unknown"}</span> · since {dateTime(me.connectedAt)}
                </p>
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <a href="/api/auth/github" className="rounded-md border border-line px-3.5 py-2 text-sm hover:border-line-strong">
                Re-authorize with GitHub
              </a>
              <Button variant="danger" onClick={() => setDisconnect("token")}>
                Disconnect GitHub
              </Button>
              <Button variant="ghost" onClick={() => setDisconnect("all")}>
                Delete all my data
              </Button>
            </div>
          </div>
        )}
      </Section>

      <form onSubmit={save} className="space-y-6" noValidate>
        <Section title="AI provider" description="The model that reviews code after static analysis. Repository code is sent to this provider.">
          <div className="space-y-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Provider" htmlFor="provider">
                <select id="provider" className={inputClass} value={provider} onChange={(e) => { setProvider(e.target.value); setModel(""); }}>
                  {settings.providers.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Model" htmlFor="model" hint={providerInfo?.defaultModel ? `Leave empty to use ${provider === settings.serverAiProvider ? "the server default" : providerInfo.defaultModel}.` : undefined}>
                <input id="model" list="model-options" className={inputClass} value={model} onChange={(e) => setModel(e.target.value)} placeholder={providerInfo?.defaultModel || "model-name"} aria-invalid={!!errors.aiModel} />
                <datalist id="model-options">
                  {providerInfo?.suggestedModels.map((m) => <option key={m} value={m} />)}
                </datalist>
                {errors.aiModel && <p className="text-xs text-sev-critical">{errors.aiModel}</p>}
              </Field>
            </div>

            <div className="rounded-md border border-line bg-night p-4">
              <p className="flex items-center gap-2 text-sm font-medium">
                <KeyRound className="size-4 text-sage" aria-hidden /> API key
              </p>
              <p className="mt-1 text-sm text-muted">
                {settings.aiKeySource === "user"
                  ? `Using your personal key ${settings.aiKeyHint ?? ""}. It is stored encrypted and never shown again.`
                  : settings.aiKeySource === "server"
                    ? "Using the key configured on the server. Add a personal key to use your own quota."
                    : "No key is available for this provider, so reviews run static analysis only until you add one."}
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <label htmlFor="api-key" className="sr-only">
                  New API key
                </label>
                <input
                  id="api-key"
                  type="password"
                  autoComplete="off"
                  className={`${inputClass} max-w-sm flex-1`}
                  placeholder="Paste a new key"
                  value={newKey}
                  onChange={(e) => setNewKey(e.target.value)}
                />
                <Button type="button" disabled={newKey.trim().length < 10} loading={setKey.isPending} onClick={() => setKey.mutate(newKey.trim(), { onSuccess: () => setNewKey("") })}>
                  Save key
                </Button>
                {settings.aiKeySource === "user" && (
                  <Button type="button" variant="ghost" loading={clearKey.isPending} onClick={() => clearKey.mutate()}>
                    Remove key
                  </Button>
                )}
              </div>
              <p className="mt-2 text-xs text-faint">Keys apply to the provider saved below. Save the provider first if you are switching.</p>
            </div>
          </div>
        </Section>

        <Section title="Review preferences" description="Which analyzers run and which findings are kept.">
          <div className="space-y-5">
            <fieldset>
              <legend className="mb-2 text-sm font-medium">Analyzers</legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {settings.availableAnalyzers.map((a) => (
                  <label key={a.id} className={`flex items-start gap-2 text-sm ${a.available ? "" : "text-faint"}`}>
                    <input type="checkbox" className="mt-0.5 accent-[var(--color-sage)]" checked={analyzers.includes(a.id)} disabled={!a.available} onChange={() => toggle(analyzers, setAnalyzers, a.id)} />
                    <span>
                      {a.label}
                      {!a.available && <span className="block text-xs">Not enabled on this server</span>}
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Minimum severity to report" htmlFor="min-severity">
                <select id="min-severity" className={inputClass} value={minSeverity} onChange={(e) => setMinSeverity(e.target.value as Severity)}>
                  {SEVERITIES.map((s) => (
                    <option key={s} value={s}>
                      {severityLabel(s)} and above
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Maximum files per review" htmlFor="max-files" hint={`Server limit: ${settings.maxFilesLimit}`}>
                <input id="max-files" type="number" min={1} max={settings.maxFilesLimit} className={inputClass} value={maxFiles} onChange={(e) => setMaxFiles(e.target.value)} aria-invalid={!!errors.maxFiles} />
                {errors.maxFiles && <p className="text-xs text-sev-critical">{errors.maxFiles}</p>}
              </Field>
            </div>
          </div>
        </Section>

        <Section title="Languages" description="Files in other languages are skipped and listed as skipped in the review.">
          <fieldset>
            <legend className="sr-only">Enabled languages</legend>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {settings.availableLanguages.map((l) => (
                <label key={l.id} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" className="accent-[var(--color-sage)]" checked={languages.includes(l.id)} onChange={() => toggle(languages, setLanguages, l.id)} />
                  {l.label}
                </label>
              ))}
            </div>
            {errors.enabledLanguages && <p className="mt-2 text-xs text-sev-critical">{errors.enabledLanguages}</p>}
          </fieldset>
        </Section>

        <div className="flex justify-end">
          <Button type="submit" variant="primary" loading={update.isPending}>
            Save settings
          </Button>
        </div>
      </form>

      <Section title="Security and privacy">
        <div className="space-y-3 text-sm text-muted">
          <p>{settings.dataRetention}</p>
          <ul className="space-y-2">
            {[
              "Findings from the AI are labelled unverified. CodeSage never executes repository code.",
              "Repository content is treated as untrusted input; instructions inside code or comments are ignored by the review policy.",
              "Pull request comments are published only after you confirm, and only as comments. CodeSage never approves, merges or pushes.",
            ].map((t) => (
              <li key={t} className="flex gap-2">
                <ShieldCheck className="mt-0.5 size-4 shrink-0 text-sage" aria-hidden /> {t}
              </li>
            ))}
          </ul>
        </div>
      </Section>

      <ConfirmDialog
        open={disconnect !== null}
        title={disconnect === "all" ? "Delete all CodeSage data?" : "Disconnect GitHub?"}
        confirmLabel={disconnect === "all" ? "Delete everything" : "Disconnect"}
        confirmVariant="danger"
        loading={disconnecting}
        onClose={() => setDisconnect(null)}
        onConfirm={doDisconnect}
      >
        {disconnect === "all"
          ? "Your reviews, findings, settings and stored GitHub token are permanently deleted, and you are signed out. This cannot be undone."
          : "The stored GitHub token is deleted and you are signed out. Your review history is kept. To fully revoke access, also remove CodeSage under GitHub Settings → Applications."}
      </ConfirmDialog>
    </div>
  );
}
