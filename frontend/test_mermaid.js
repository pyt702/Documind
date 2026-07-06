import mermaid from 'mermaid';

mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });

mermaid.parse(`graph TD
  A["Nebula (Gas & Dust)"] --> B["Protostar"]
  B --> C["Main-Sequence Star"]

  C --> D{"Mass of Star"}

  D -->|Sun-like (Mid-sized)| E["Subgiant Phase"]
  E --> F["Red-Giant Phase"]
`).then(console.log).catch(console.error);
