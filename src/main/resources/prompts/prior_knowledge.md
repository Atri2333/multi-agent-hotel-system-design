# Multi-Agent Hotel System Design (ADD-style)

This document is the single source of background knowledge for the multi-agent system.

## Global Rules (authoritative)

### Language Policy
- All deliverables intended for submission/reporting must be written in English.

### Knowledge Policy
- Use only the information provided in:
  - `prior_knowledge` (this document), and
  - `compacted_history` (summarized outputs from prior iterations).
- If a needed detail is missing, state assumptions explicitly and keep them minimal.

### Interaction Protocol
- Human checkpoint accepted inputs only: `approve` / `retry`

### Output Contract
- All agents output a single JSON object (no surrounding prose, no code fences).
- JSON fields used by this system (as applicable by role):
  - `design` (string)
  - `diagram_code` (string, Mermaid only)
  - `issues` (array of strings)
  - `decision_log` (array of strings)

### Process Constraints
- Run 4 iterations, sequential.
- Critic–Architect revision loop is allowed up to 2 revisions per iteration (max 2).

## Agents
- Orchestrator: plan iteration goals, route nodes
- Architect: produce design + Mermaid
- Critic: QA/constraints check, request revision (max 2)
- Scribe: consolidate final, log decisions and rationale
- Context Compactor: summarize history to avoid context overflow;must not introduce new design decisions or external knowledge beyond what has been stated.

---

## ADD 3.0 Method (Attribute-Driven Design)

### Step 1 Review Inputs
The first step of the ADD method involves reviewing the inputs and identifying which requirements will be considered as architectural drivers.

### Step 2 Establish the Iteration Goal by Selecting Drivers
A design round generally takes the form of a series of design iterations, where each iteration focuses on achieving a particular goal. Such a goal typically involves designing to satisfy a subset of the drivers.

### Step 3 Choose One or More Elements of the System to Refine
This step is where the core design activities start. The elements that you will select are the ones that are involved in the satisfaction of specific drivers. For greenfield development, you can start by establishing the system context and then selecting the only available element—that is, the system itself—for refinement by decomposition. For existing systems or for later design iterations in greenfield systems, you would normally choose to refine elements that were identified in prior iterations.

### Step 4 Choose One or More Design Concepts That Satisfy the Selected Drivers
This step requires you to identify alternatives among design concepts that can be used to achieve your iteration goal, and to select one of these alternatives.

### Step 5 Instantiate Architectural Elements, Allocate Responsibilities, and Define Interfaces
This step requires instantiating architectural elements based on the selected design concepts and assigning responsibilities to them. In addition, relationships and interfaces between elements need to be established so that they can collaborate and exchange information effectively.

### Step 6 Sketch Views and Record Design Decisions
At this point, you have completed the design activities for the iteration. In addition to preserving the views—the representations of the structures you created, you should also record the significant decisions made during the design iteration, as well as the reasons behind those decisions (i.e., the rationale), to facilitate later analysis and understanding.

### Step 7 Perform Analysis of Current Design and Review Iteration Goal and Achievement of Design Purpose
This step checks whether a partial design that satisfies the goals of the current iteration has been created, and considers whether additional design iterations are needed.

---

## Case Study: Hotel Pricing System (HPS)

### Design Purpose
This project can be considered greenfield development, as it involves the complete replacement of an existing system. The purpose of the design activity is to make initial decisions to support the construction of the system from scratch.

### Primary Functionality

#### Use Cases
- **HPS-1: Log In**: A user (commercial or administrator) provides their credentials in a login window. The system checks these credentials against a user identity service and, if successful, provides access to the system. Once logged in, a user can only make queries and changes to the hotels for which they have been authorized.
- **HPS-2: Change Prices**: A user selects a specific hotel for which they are authorized to change prices, and selects dates where they want to make price changes to either a base rate or a fixed rate. All of the prices for the rates that are calculated from the base rate are calculated at that point. The system allows price changes to be simulated before they are actually changed. When the prices are changed, they are pushed to the Channel Management System and become available for querying by external systems.
- **HPS-3: Query Prices**: A user or an external system queries prices for a given hotel through the user interface or a query API.
- **HPS-4: Manage Hotels**: An administrator adds, changes, or modifies hotel information. This includes editing the hotel's tax rates, available rates, and room types.
- **HPS-5: Manage Rates**: An administrator adds, changes, or modifies rates. This includes defining the calculation business rules for the different rates.
- **HPS-6: Manage Users**: An administrator changes permissions for a given user.

### Quality Attributes

| ID | Quality Attribute | Scenario | Associated Use Case | Importance to the Customer | Difficulty of Implementation |
|---|---|---|---|---|---|
| QA-1 | Performance | A base rate price is changed for a specific hotel and date during normal operation; the prices for all the rates and room types for the hotel are published (ready for query) in less than 100 ms. | HPS-2 | High | High |
| QA-2 | Reliability | A user performs multiple price changes on a given hotel; 100% of the price changes are published (available for query) successfully and are also received by the Channel Management System. | HPS-2 | High | High |
| QA-3 | Availability | Pricing queries uptime SLA must be 99.9% outside of maintenance windows. | All | High | High |
| QA-4 | Scalability | The system will initially support a minimum of 100,000 price queries per day through its API and should be capable of handling up to 1,000,000 without decreasing average latency by more than 20%. | HPS-3 | High | High |
| QA-5 | Security | A user logs into the system through the front-end. The credentials of the user are validated against the User Identity Service and, once logged in, they are presented with only the functions that they are authorized to use. | All | High | Medium |
| QA-6 | Modifiability | Support for a price query endpoint with a different protocol than REST (e.g., gRPC) is added to the system. The new endpoint does not require changes to be made to the core components of the system. | All | Medium | Medium |
| QA-7 | Deployability | The application is moved between nonproduction environments as part of the development process. No changes in the code are needed. | All | Medium | Medium |
| QA-8 | Monitorability | A system operator wishes to measure the performance and reliability of price publication during operation. The system provides a mechanism that allows 100% of these measures to be collected as needed. | HPS-2 | Medium | Medium |
| QA-9 | Testability | 100% of the system and its elements should support integration testing independently of the external systems. | All | Medium | Medium |

### Architectural Concerns

| ID | Concern |
|---|---|
| CRN-1 | Establish an overall initial system structure. |
| CRN-2 | Leverage the team's knowledge about Java technologies, the Angular framework, and Kafka. |
| CRN-3 | Allocate work to members of the development team. |
| CRN-4 | Avoid introducing technical debt. |
| CRN-5 | Set up a continuous deployment infrastructure. |

### Constraints

| ID | Constraint |
|---|---|
| CON-1 | Users must interact with the system through a web browser in different platforms (Windows, OSX, and Linux, and different devices). |
| CON-2 | Manage users through cloud provider identity service and host resources in the cloud. |
| CON-3 | Code must be hosted on a proprietary Git-based platform that is already in use by other projects in the company. |
| CON-4 | The initial release of the system must be delivered in 6 months, but an initial version of the system (MVP) must be demonstrated to internal stakeholders in at most 2 months. |
| CON-5 | The system must interact initially with existing systems through REST APIs but may need to later support other protocols. |
| CON-6 | A cloud-native approach should be favored when designing the system. |
